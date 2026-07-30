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
 * All user-facing text is a string resource — never a raw exception message, hostname, or
 * other technical detail (matching the rest of the app's error-string convention, e.g.
 * `RoutineDetailsViewModel`/`RoutineCreateViewModel`).
 */
@Singleton
class RoutineNotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun build(model: RoutineNotificationModel): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(context, RoutineNotificationIds.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blick)
            .setContentTitle(model.stationName)
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

        applyContent(builder, model)

        return builder.build()
    }

    private fun applyContent(builder: NotificationCompat.Builder, model: RoutineNotificationModel) {
        val summary = context.getString(
            R.string.notification_header_format,
            model.lineLabel ?: context.getString(R.string.notification_line_fallback),
            model.directionLabel ?: context.getString(R.string.notification_direction_fallback),
        )

        // Line/direction context must remain visible in every state (Live, Stale,
        // NoUpcomingDepartures, Offline, Unavailable, Loading) -- setSubText is the standard
        // NotificationCompat slot for this persistent secondary context, distinct from the
        // state-specific status message in setContentText below, so collapsed notifications
        // never show the same summary text twice.
        builder.setSubText(summary)

        when (val content = model.content) {
            is RoutineNotificationContent.Live -> {
                val rows = rowLines(content.departures)
                // The collapsed content line shows the soonest departure's own row at a
                // glance rather than repeating `summary` a second time (already shown via
                // setSubText above).
                builder.setContentText(rows.firstOrNull())
                builder.setStyle(inboxStyle(summary, rows))
            }
            is RoutineNotificationContent.Stale -> {
                val staleText = context.getString(R.string.notification_stale_warning)
                builder.setContentText(staleText)
                val lines = rowLines(content.departures).ifEmpty { listOf(context.getString(R.string.notification_no_departures)) } +
                    lastCheckedLine(content.lastCheckedAt)
                builder.setStyle(inboxStyle(staleText, lines))
            }
            is RoutineNotificationContent.NoUpcomingDepartures -> {
                val text = context.getString(R.string.notification_no_departures)
                builder.setContentText(text)
                builder.setStyle(inboxStyle(text, listOf(lastCheckedLine(content.lastCheckedAt))))
            }
            is RoutineNotificationContent.Offline -> {
                builder.setContentText(context.getString(R.string.notification_offline))
            }
            is RoutineNotificationContent.Unavailable -> {
                builder.setContentText(context.getString(R.string.notification_unavailable))
            }
            is RoutineNotificationContent.Loading -> {
                builder.setContentText(context.getString(R.string.notification_loading))
            }
        }
    }

    private fun rowLines(departures: List<NotificationDepartureRow>): List<String> = departures.map { row ->
        val destination = row.destinationLabel ?: context.getString(R.string.direction_unknown_destination)
        if (row.isCancelled) {
            // Cancellation takes priority over real-time/scheduled status, matching the
            // existing routine-details-screen convention (departureStatusLabel) — a future
            // cancelled departure is still shown, just clearly marked, with no countdown.
            context.getString(R.string.notification_row_cancelled_format, context.getString(R.string.routine_details_departure_cancelled), row.lineDesignation, destination)
        } else {
            val statusText = context.getString(
                if (row.isRealTime) R.string.routine_details_departure_live else R.string.routine_details_departure_scheduled,
            )
            context.getString(R.string.notification_row_format, row.minutesRemaining, statusText, row.lineDesignation, destination)
        }
    }

    private fun lastCheckedLine(lastCheckedAt: java.time.Instant): String =
        context.getString(R.string.notification_last_checked_format, formatDepartureTime(lastCheckedAt, Locale.getDefault()))

    /** [androidx.core.app.NotificationCompat.InboxStyle] is used for the expanded view since
     * it renders up to two departure lines cleanly; [summary] repeats the collapsed
     * line+direction summary as the style's own summary text so the expanded view doesn't
     * lose that context. */
    private fun inboxStyle(summary: String, lines: List<String>): NotificationCompat.InboxStyle {
        val style = NotificationCompat.InboxStyle().setSummaryText(summary)
        lines.forEach { style.addLine(it) }
        return style
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
