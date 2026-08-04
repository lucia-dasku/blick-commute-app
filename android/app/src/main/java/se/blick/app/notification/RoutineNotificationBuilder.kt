package se.blick.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import se.blick.app.MainActivity
import se.blick.app.R
import se.blick.app.ui.screens.routinedetails.formatDepartureTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the real [Notification] for a [RoutineNotificationModel]. Kept entirely separate
 * from [AndroidRoutineNotifier] (which only ever posts/cancels the id this class targets) so
 * the construction itself — channel, flags, content, expanded style — can be unit-tested
 * directly against the built [Notification] (see the Robolectric-based
 * `RoutineNotificationBuilderTest`) without needing a real `NotificationManager` to post to.
 *
 * Deliberately simple: only standard [NotificationCompat] fields and [NotificationCompat.BigTextStyle]
 * — no custom `RemoteViews`, colours, fonts, sizes, or spacing. The title (route/line/
 * destination, e.g. "14 · Slussen → Fruängen" — bolded automatically by the platform, not by
 * this class) is the one place that identity is shown; the body never repeats it. The
 * collapsed body is at most three short lines — the soonest departure's own countdown and
 * status, the following departure's own countdown (only when a second departure exists), and
 * a fixed disruption indicator (only when a relevant disruption exists) — joined with `\n`
 * into [NotificationCompat.Builder.setContentText]. Which of those lines Samsung (or any other
 * OEM launcher/shade) actually renders collapsed, and whether it truncates any of them, is
 * entirely up to the platform; this class does not attempt to control it.
 *
 * All user-facing text is a string resource — never a raw exception message, hostname, or
 * other technical detail (matching the rest of the app's error-string convention, e.g.
 * `RoutineDetailsViewModel`/`RoutineCreateViewModel`).
 *
 * Every notification this builds requests Android 16's promoted-ongoing ("Live Update")
 * surface via [NotificationCompat.Builder.setRequestPromotedOngoing] — the prominent
 * lock-screen card (Samsung's Now Bar where supported), not merely a notification-shade
 * entry. That is a *request* only: the OS and OEM still decide whether to actually promote
 * it (device/OS version — Android 16+ only — user settings, and other active promoted
 * notifications all factor in; see [AndroidNotificationAvailabilityChecker]'s own doc on
 * checking this), so the exact same [Notification] this builds is also a perfectly valid
 * plain ongoing notification on any device/state where promotion doesn't happen — there is
 * no separate "unpromoted" code path to fall back to. Promoted notifications are
 * restricted to a handful of styles (`BigTextStyle` among them); [NotificationCompat.InboxStyle]
 * is not one of them, which is why [bigTextStyle], not an inbox style, renders the expanded
 * view — though "expanded" is aspirational once a notification is actually promoted: verified
 * directly on a real Android 16 device via `uiautomator dump`, a promoted-ongoing notification's
 * row has no `expand_button` at all (ordinary notifications in the same shade do), so
 * whatever [bigTextStyle] contains is unconditionally shown, with no collapsed state to expand
 * from. Because of that, a disruption's own real header/details are never placed in
 * [bigTextStyle] at all — only the fixed indicator string, identically in both the collapsed
 * body and the "expanded" one — so the real message can never be shown at a glance regardless
 * of whether this specific notification instance ends up promoted or not. The real message is
 * only ever read by tapping the notification into Routine Details' own Disruptions section.
 *
 * Also always adds a Stop action (the spec's "Stop/Unpin" control) — a plain
 * [NotificationCompat.Action], not a custom view, so it stays valid on the promoted surface too
 * — whose [PendingIntent] targets [StopRoutineNotificationReceiver] and, in turn,
 * [StopRoutineNotificationAction], which stops today's active window early (same effect as
 * "pause for today") and removes this notification.
 */
@Singleton
class RoutineNotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun build(model: RoutineNotificationModel): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(context, RoutineNotificationIds.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blick)
            .setContentTitle(title(model))
            .setContentIntent(contentIntent(model.routineId))
            // A continuously-updating commute notification is only ever removed by the app
            // itself (RoutineNotifier.remove()) or when the routine's time window ends (once
            // scheduling exists) — never by the user swiping it away or opening it.
            .setOngoing(true)
            // Repeated showOrUpdate() calls (e.g. every refresh, once scheduling exists) must
            // never re-alert/re-sound/re-heads-up; only the very first post should.
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // See this class's own doc — a request, not a guarantee; requires the
            // POST_PROMOTED_NOTIFICATIONS manifest permission (AndroidManifest.xml).
            .setRequestPromotedOngoing(true)
            .addAction(stopAction(model.routineId))

        applyContent(builder, model)

        return builder.build()
    }

    /** The one place route/line/destination is shown — see this class's own doc on why the
     * body never repeats it. Rendered bold by the platform's own standard title styling; no
     * markup is applied here. */
    private fun title(model: RoutineNotificationModel): String = context.getString(
        R.string.notification_title_format,
        model.lineLabel ?: context.getString(R.string.notification_line_fallback),
        model.stationName,
        model.directionLabel ?: context.getString(R.string.notification_direction_fallback),
    )

    private fun applyContent(builder: NotificationCompat.Builder, model: RoutineNotificationModel) {
        val hasDisruption = model.disruptionHeadline != null
        // The one, and only, change a disruption is allowed to make to this notification's
        // own text: a fixed, translation-safe line flagging that something exists, never the
        // disruption's own header/details. See this class's own doc for why: a promoted-ongoing
        // notification (Android 16's Live Update / Samsung's Now Bar) has no collapse state at
        // all once eligible for promotion -- whatever BigTextStyle.bigText() contains is always
        // fully visible, so there is no "expand to reveal" gate to hide the real message behind.
        // The real message is only ever shown by tapping into Routine Details' own Disruptions
        // section, which is exactly what "Tap for details" refers to.
        val disruptionIndicator = if (hasDisruption) listOf(context.getString(R.string.notification_disruption_available)) else emptyList()

        when (val content = model.content) {
            is RoutineNotificationContent.Live -> {
                val bodyLines = departureLines(content.departures)
                builder.setContentText((bodyLines + disruptionIndicator).joinToString("\n"))
                builder.setStyle(bigTextStyle(bodyLines + disruptionIndicator))
                shortCriticalText(content.departures)?.let { builder.setShortCriticalText(it) }
            }
            is RoutineNotificationContent.Stale -> {
                // The last-known departure lines are informative enough to keep in the
                // expanded body (see this class's own doc on why the title never repeats, but
                // a departure's own countdown isn't route/line/destination) without cluttering
                // the three-line collapsed budget.
                val staleText = context.getString(R.string.notification_stale_warning)
                builder.setContentText((listOf(staleText) + disruptionIndicator).joinToString("\n"))
                val expandedLines = listOf(staleText) + departureLines(content.departures) +
                    listOf(lastCheckedLine(content.lastCheckedAt)) + disruptionIndicator
                builder.setStyle(bigTextStyle(expandedLines))
                shortCriticalText(content.departures)?.let { builder.setShortCriticalText(it) }
            }
            is RoutineNotificationContent.NoUpcomingDepartures -> {
                val text = context.getString(R.string.notification_no_departures)
                builder.setContentText((listOf(text) + disruptionIndicator).joinToString("\n"))
                builder.setStyle(bigTextStyle(listOf(text, lastCheckedLine(content.lastCheckedAt)) + disruptionIndicator))
            }
            is RoutineNotificationContent.Offline -> {
                val text = context.getString(R.string.notification_offline)
                builder.setContentText((listOf(text) + disruptionIndicator).joinToString("\n"))
                if (hasDisruption) builder.setStyle(bigTextStyle(listOf(text) + disruptionIndicator))
            }
            is RoutineNotificationContent.Unavailable -> {
                val text = context.getString(R.string.notification_unavailable)
                builder.setContentText((listOf(text) + disruptionIndicator).joinToString("\n"))
                if (hasDisruption) builder.setStyle(bigTextStyle(listOf(text) + disruptionIndicator))
            }
            is RoutineNotificationContent.Loading -> {
                val text = context.getString(R.string.notification_loading)
                builder.setContentText((listOf(text) + disruptionIndicator).joinToString("\n"))
                if (hasDisruption) builder.setStyle(bigTextStyle(listOf(text) + disruptionIndicator))
            }
        }
    }

    /** Up to two lines: the soonest departure's own countdown+status (or "Cancelled"), then
     * the following departure's own countdown (or "Cancelled") -- omitted entirely when there
     * is no following departure, never padded or placeheld. Neither line repeats the line
     * designation or destination, both already shown once in the title (see this class's own
     * doc). */
    private fun departureLines(departures: List<NotificationDepartureRow>): List<String> =
        listOfNotNull(
            departures.getOrNull(0)?.let(::primaryDepartureLine),
            departures.getOrNull(1)?.let(::nextDepartureLine),
        )

    private fun primaryDepartureLine(row: NotificationDepartureRow): String =
        if (row.isCancelled) {
            // Cancellation takes priority over real-time/scheduled status and drops the
            // countdown entirely, matching the existing routine-details-screen convention
            // (departureStatusLabel).
            context.getString(R.string.routine_details_departure_cancelled)
        } else {
            val statusText = context.getString(
                if (row.isRealTime) R.string.routine_details_departure_live else R.string.routine_details_departure_scheduled,
            )
            context.getString(R.string.notification_departure_status_format, row.minutesRemaining, statusText)
        }

    private fun nextDepartureLine(row: NotificationDepartureRow): String =
        if (row.isCancelled) {
            context.getString(R.string.notification_next_departure_cancelled)
        } else {
            context.getString(R.string.notification_next_departure_format, row.minutesRemaining)
        }

    private fun lastCheckedLine(lastCheckedAt: java.time.Instant): String =
        context.getString(R.string.notification_last_checked_format, formatDepartureTime(lastCheckedAt, Locale.getDefault()))

    /** [androidx.core.app.NotificationCompat.BigTextStyle] renders the expanded view —
     * [NotificationCompat.InboxStyle] is not one of the styles Android 16's promoted-ongoing
     * surface allows (see this class's own doc), while `BigTextStyle` is. [lines] are joined
     * into one wrapped text block rather than InboxStyle's separate bullet lines — the closest
     * equivalent this style permits. */
    private fun bigTextStyle(lines: List<String>): NotificationCompat.BigTextStyle =
        NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))

    /** The promoted surface's small "status chip" text (see
     * [androidx.core.app.NotificationCompat.Builder.setShortCriticalText]) — the soonest
     * departure's own countdown (or "Cancelled"), the single most critical fact this
     * notification has to offer at a glance. Returns null (skip the chip entirely) when
     * there is no departure to summarize — see [RoutineNotificationContent.Stale]'s own doc
     * on why its `departures` can be empty. */
    private fun shortCriticalText(departures: List<NotificationDepartureRow>): String? {
        val soonest = departures.firstOrNull() ?: return null
        return if (soonest.isCancelled) {
            context.getString(R.string.routine_details_departure_cancelled)
        } else {
            context.getString(R.string.routine_details_minutes_remaining, soonest.minutesRemaining)
        }
    }

    /** The "Stop/Unpin" action — see this class's own doc. Uses the dedicated stop icon rather
     * than the small icon: an action icon is a distinct visual slot from the status-bar small
     * icon, and reusing an unrelated glyph there would misrepresent what tapping it does. */
    private fun stopAction(routineId: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_stat_stop,
            context.getString(R.string.notification_action_stop),
            stopIntent(routineId),
        ).build()

    private fun stopIntent(routineId: String): PendingIntent {
        val intent = Intent(context, StopRoutineNotificationReceiver::class.java).apply {
            putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, routineId)
        }
        return PendingIntent.getBroadcast(
            context,
            RoutineNotificationIds.STOP_ACTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun contentIntent(routineId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, routineId)
        }
        return PendingIntent.getActivity(
            context,
            RoutineNotificationIds.CONTENT_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Idempotent: recreating a channel with the same id and unchanged attributes is a no-op
     * per the platform's own `createNotificationChannel` contract — no "already exists" guard
     * is needed here. `minSdk = 26` means channels are always available; no
     * `Build.VERSION.SDK_INT` branch is needed (see [RoutineNotifier]'s doc comment). */
    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            RoutineNotificationIds.CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // A continuously-updating commute notification must never make sound/heads-up
            // pop on every refresh — IMPORTANCE_LOW shows it in the shade without either.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
