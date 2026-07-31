package se.blick.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.blick.app.MainActivity
import se.blick.app.R
import se.blick.app.ui.screens.routinedetails.formatDepartureTime
import java.time.Instant
import java.util.Locale

/**
 * Robolectric-backed tests asserting on the REAL constructed [Notification] and, for
 * [AndroidRoutineNotifier], the real framework [NotificationManager] — not a second
 * hand-rolled copy of the expected configuration. See `libs.versions.toml`'s `robolectric`
 * entry for why this targets `@Config(sdk = [34])` rather than this project's actual
 * `compileSdk`/`targetSdk` of 36, and why `application = android.app.Application::class` is
 * used (this class constructs [RoutineNotificationBuilder]/[AndroidRoutineNotifier] directly
 * with a plain [Context]; no Hilt component needs to spin up).
 *
 * Kept as its own test class, separate from `RoutineNotificationMapperTest` (a plain JVM
 * suite with no Android/Robolectric dependency at all) — see this milestone's Part 7
 * requirement that the two layers stay in separate test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoutineNotificationBuilderTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val builder = RoutineNotificationBuilder(context)

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private fun model(
        routineId: String = "r1",
        stationName: String = "Fruängen",
        lineLabel: String? = "14",
        directionLabel: String? = "Fruängen",
        content: RoutineNotificationContent = RoutineNotificationContent.Live(listOf(sampleRow())),
    ) = RoutineNotificationModel(routineId, stationName, lineLabel, directionLabel, content)

    private fun sampleRow(
        lineDesignation: String = "14",
        destinationLabel: String? = "Fruängen",
        effectiveTime: Instant = now.plusSeconds(240),
        minutesRemaining: Long = 4,
        isRealTime: Boolean = true,
        isCancelled: Boolean = false,
    ) = NotificationDepartureRow(lineDesignation, destinationLabel, effectiveTime, minutesRemaining, isRealTime, isCancelled)

    // ---- Channel ----

    @Test
    fun `building creates the expected channel with quiet importance`() {
        builder.build(model())
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID)
        assertEquals(RoutineNotificationIds.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `channel creation is idempotent across repeated builds`() {
        builder.build(model())
        builder.build(model())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.notificationChannels.size)
    }

    // ---- Core flags ----

    @Test
    fun `the built notification uses the dedicated small icon`() {
        val notification = builder.build(model())
        assertEquals(R.drawable.ic_stat_blick, notification.smallIcon.resId)
    }

    @Test
    fun `the built notification is ongoing, only-alert-once, not auto-cancel, and publicly visible`() {
        val notification = builder.build(model())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL == 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
    }

    // ---- Content intent (Part 5: tap navigation) ----

    @Test
    fun `the content PendingIntent targets MainActivity with the model's routine id`() {
        val notification = builder.build(model(routineId = "r-42"))
        val savedIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        assertEquals("r-42", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `rebuilding for a different routine id updates the content intent's extras`() {
        builder.build(model(routineId = "r1"))
        val second = builder.build(model(routineId = "r2"))
        val savedIntent = shadowOf(second.contentIntent).savedIntent
        assertEquals("r2", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    // ---- Stop action ("Stop/Unpin" control) ----

    @Test
    fun `the built notification has exactly one Stop action targeting StopRoutineNotificationReceiver with the model's routine id`() {
        val notification = builder.build(model(routineId = "r-42"))
        assertEquals(1, notification.actions.size)
        val action = notification.actions.single()
        assertEquals(context.getString(R.string.notification_action_stop), action.title.toString())
        val savedIntent = shadowOf(action.actionIntent).savedIntent
        assertEquals(StopRoutineNotificationReceiver::class.java.name, savedIntent.component?.className)
        assertEquals("r-42", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `rebuilding for a different routine id updates the Stop action's extras`() {
        builder.build(model(routineId = "r1"))
        val second = builder.build(model(routineId = "r2"))
        val savedIntent = shadowOf(second.actions.single().actionIntent).savedIntent
        assertEquals("r2", savedIntent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    // ---- Title / collapsed content ----

    @Test
    fun `title is the station name`() {
        val notification = builder.build(model(stationName = "Slussen"))
        assertEquals("Slussen", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `missing pinned line and direction use fallback wording in the subtext summary`() {
        val notification = builder.build(model(lineLabel = null, directionLabel = null))
        val summary = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()
        assertTrue(summary.contains(context.getString(R.string.notification_line_fallback)))
        assertTrue(summary.contains(context.getString(R.string.notification_direction_fallback)))
    }

    // ---- Line/direction context preserved in every state (Fix 4) ----

    private fun subText(notification: Notification): String = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()

    @Test
    fun `Live preserves the line and direction summary as subtext`() {
        val notification = builder.build(model(lineLabel = "14", directionLabel = "Fruängen"))
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
    }

    @Test
    fun `Stale preserves the line and direction summary as subtext`() {
        val notification = builder.build(
            model(lineLabel = "14", directionLabel = "Fruängen", content = RoutineNotificationContent.Stale(listOf(sampleRow()), now)),
        )
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
    }

    @Test
    fun `NoUpcomingDepartures preserves the line and direction summary as subtext`() {
        val notification = builder.build(
            model(lineLabel = "14", directionLabel = "Fruängen", content = RoutineNotificationContent.NoUpcomingDepartures(now)),
        )
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
    }

    @Test
    fun `Offline preserves the line and direction summary as subtext alongside the offline message`() {
        val notification = builder.build(
            model(lineLabel = "14", directionLabel = "Fruängen", content = RoutineNotificationContent.Offline),
        )
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
        assertEquals(context.getString(R.string.notification_offline), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `Unavailable preserves the line and direction summary as subtext alongside the unavailable message`() {
        val notification = builder.build(
            model(lineLabel = "14", directionLabel = "Fruängen", content = RoutineNotificationContent.Unavailable),
        )
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
        assertEquals(context.getString(R.string.notification_unavailable), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `Loading preserves the line and direction summary as subtext alongside the loading message`() {
        val notification = builder.build(
            model(lineLabel = "14", directionLabel = "Fruängen", content = RoutineNotificationContent.Loading),
        )
        assertTrue(subText(notification).contains("14"))
        assertTrue(subText(notification).contains("Fruängen"))
        assertEquals(context.getString(R.string.notification_loading), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `missing line falls back to the line-fallback wording in the subtext, in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        states.forEach { content ->
            val notification = builder.build(model(lineLabel = null, content = content))
            assertTrue(
                "expected line fallback in subtext for $content",
                subText(notification).contains(context.getString(R.string.notification_line_fallback)),
            )
        }
    }

    @Test
    fun `missing direction falls back to the direction-fallback wording in the subtext, in every state`() {
        val states = listOf(
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.Live(listOf(sampleRow())),
        )
        states.forEach { content ->
            val notification = builder.build(model(directionLabel = null, content = content))
            assertTrue(
                "expected direction fallback in subtext for $content",
                subText(notification).contains(context.getString(R.string.notification_direction_fallback)),
            )
        }
    }

    // ---- Expanded departure lines ----
    //
    // NotificationCompat.BigTextStyle (Notification.EXTRA_BIG_TEXT), not InboxStyle
    // (Notification.EXTRA_TEXT_LINES) -- see RoutineNotificationBuilder's own class doc on
    // why: Android 16's promoted-ongoing ("Live Update") surface only allows a handful of
    // styles, and InboxStyle is not one of them. The up-to-two rows are joined by "\n" into
    // one wrapped text block instead of separate bulleted lines.

    private fun bigTextLines(notification: Notification): List<String> =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split("\n")

    @Test
    fun `Live content shows up to two expanded departure lines`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(minutesRemaining = 4, isRealTime = true), sampleRow(minutesRemaining = 11, isRealTime = false)),
                ),
            ),
        )
        val lines = bigTextLines(notification)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("4"))
        assertTrue(lines[0].contains(context.getString(R.string.routine_details_departure_live)))
        assertTrue(lines[1].contains("11"))
        assertTrue(lines[1].contains(context.getString(R.string.routine_details_departure_scheduled)))
    }

    @Test
    fun `a cancelled departure's line states cancellation ahead of any countdown`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(isCancelled = true, minutesRemaining = 4, isRealTime = true)))),
        )
        val line = bigTextLines(notification).single()
        assertTrue(line.contains(context.getString(R.string.routine_details_departure_cancelled)))
        // Checking for the literal countdown suffix ("4 min", from notification_row_format's
        // "%1$d min" segment) rather than a bare "4 " -- the sample row's own line designation
        // is "14", so "4 " is also a substring of "14 →" and a bare check false-fails here even
        // though no countdown is actually present.
        assertFalse(line.contains("4 min"))
    }

    // ---- Distinct state content ----

    @Test
    fun `Stale content states that refresh failed and includes a last-checked time`() {
        val lastCheckedAt = now.minusSeconds(600)
        val notification = builder.build(
            model(content = RoutineNotificationContent.Stale(listOf(sampleRow()), lastCheckedAt)),
        )
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals(context.getString(R.string.notification_stale_warning), text)

        val expectedTimeText = formatDepartureTime(lastCheckedAt, Locale.getDefault())
        assertTrue(bigTextLines(notification).any { it.contains(expectedTimeText) })
    }

    // ---- Promoted-ongoing (Live Update) request ----

    @Test
    fun `every built notification requests promoted-ongoing status`() {
        val states = listOf(
            RoutineNotificationContent.Live(listOf(sampleRow())),
            RoutineNotificationContent.Stale(listOf(sampleRow()), now),
            RoutineNotificationContent.NoUpcomingDepartures(now),
            RoutineNotificationContent.Offline,
            RoutineNotificationContent.Unavailable,
            RoutineNotificationContent.Loading,
        )
        states.forEach { content ->
            val notification = builder.build(model(content = content))
            assertTrue(
                "expected setRequestPromotedOngoing(true) for $content",
                androidx.core.app.NotificationCompat.isRequestPromotedOngoing(notification),
            )
        }
    }

    @Test
    fun `Live content sets the soonest departure's countdown as the short critical text`() {
        val notification = builder.build(
            model(
                content = RoutineNotificationContent.Live(
                    listOf(sampleRow(minutesRemaining = 4), sampleRow(minutesRemaining = 19)),
                ),
            ),
        )
        assertEquals(
            context.getString(R.string.routine_details_minutes_remaining, 4L),
            androidx.core.app.NotificationCompat.getShortCriticalText(notification),
        )
    }

    @Test
    fun `a cancelled soonest departure sets the cancelled label as the short critical text`() {
        val notification = builder.build(
            model(content = RoutineNotificationContent.Live(listOf(sampleRow(isCancelled = true)))),
        )
        assertEquals(
            context.getString(R.string.routine_details_departure_cancelled),
            androidx.core.app.NotificationCompat.getShortCriticalText(notification),
        )
    }

    @Test
    fun `no short critical text is set when there is no departure to summarize`() {
        val notification = builder.build(model(content = RoutineNotificationContent.NoUpcomingDepartures(now)))
        assertEquals(null, androidx.core.app.NotificationCompat.getShortCriticalText(notification))
    }

    @Test
    fun `NoUpcomingDepartures content is distinct from Offline and Unavailable`() {
        val offlineText = builder.build(model(content = RoutineNotificationContent.Offline))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val unavailableText = builder.build(model(content = RoutineNotificationContent.Unavailable))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val noDeparturesText = builder.build(model(content = RoutineNotificationContent.NoUpcomingDepartures(now)))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        assertEquals(context.getString(R.string.notification_offline), offlineText)
        assertEquals(context.getString(R.string.notification_unavailable), unavailableText)
        assertEquals(context.getString(R.string.notification_no_departures), noDeparturesText)
        assertEquals(3, setOf(offlineText, unavailableText, noDeparturesText).size)
    }

    @Test
    fun `Loading content shows a quiet updating message`() {
        val notification = builder.build(model(content = RoutineNotificationContent.Loading))
        assertEquals(
            context.getString(R.string.notification_loading),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }
}

/**
 * Covers [AndroidRoutineNotifier]'s own responsibilities — posting/updating/cancelling a
 * single stable notification id via the real framework [NotificationManager] — separately
 * from [RoutineNotificationBuilder]'s construction concerns above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidRoutineNotifierTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val availabilityChecker = AndroidNotificationAvailabilityChecker(context)
    private val notifier = AndroidRoutineNotifier(context, RoutineNotificationBuilder(context), availabilityChecker)
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    // POST_NOTIFICATIONS (API 33+) is a dangerous permission -- declaring it in the manifest
    // does not grant it, matching real first-install device behavior, so Robolectric denies it
    // by default. Every test in this class is about the app-wide toggle/channel state, not the
    // runtime permission itself, so it's granted upfront here.
    @Before
    fun grantNotificationPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun model(stationName: String = "Fruängen") = RoutineNotificationModel(
        routineId = "r1",
        stationName = stationName,
        lineLabel = "14",
        directionLabel = "Fruängen",
        content = RoutineNotificationContent.NoUpcomingDepartures(now),
    )

    @Test
    fun `showOrUpdate posts under the stable notification id`() {
        notifier.showOrUpdate(model())
        val posted = manager.activeNotifications.singleOrNull { it.id == RoutineNotificationIds.NOTIFICATION_ID }
        assertTrue(posted != null)
    }

    @Test
    fun `repeated showOrUpdate calls update the same notification id rather than posting a new one`() {
        notifier.showOrUpdate(model(stationName = "Fruängen"))
        notifier.showOrUpdate(model(stationName = "Slussen"))

        assertEquals(1, manager.activeNotifications.size)
        val posted = manager.activeNotifications.single { it.id == RoutineNotificationIds.NOTIFICATION_ID }
        assertEquals("Slussen", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `remove cancels the posted notification`() {
        notifier.showOrUpdate(model())
        assertEquals(1, manager.activeNotifications.size)

        notifier.remove()

        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `remove without a prior post is a harmless no-op`() {
        notifier.remove()
        assertEquals(0, manager.activeNotifications.size)
    }

    // ---- NotificationPostResult (Fix 3) ----

    @Test
    fun `a successful post returns Posted`() {
        val result = notifier.showOrUpdate(model())
        assertEquals(NotificationPostResult.Posted, result)
    }

    @Test
    fun `disabled notifications return NotificationsDisabled and post nothing`() {
        shadowOf(manager).setNotificationsEnabled(false)

        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.NotificationsDisabled, result)
        assertEquals(0, manager.activeNotifications.size)
    }

    // ---- Disabled Blick channel specifically (distinct from the app-wide toggle above) ----

    @Test
    fun `app notifications enabled and the Blick channel enabled -- a post succeeds`() {
        // No channel exists yet in a fresh Robolectric environment; showOrUpdate's own
        // ensureChannel() creates it (at IMPORTANCE_LOW, i.e. enabled) as part of a normal post.
        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.Posted, result)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun `app notifications enabled but the Blick channel importance is IMPORTANCE_NONE -- NotificationsDisabled, nothing posted`() {
        // Simulates the user having disabled specifically the Blick channel (distinct from
        // disabling notifications for the whole app) via a REAL NotificationChannel at the
        // real framework NotificationManager -- not a hand-rolled duplicate of the notifier's
        // own logic.
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        val result = notifier.showOrUpdate(model())

        assertEquals(NotificationPostResult.NotificationsDisabled, result)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `a disabled Blick channel is reported as NotificationsDisabled, never as Failed`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        val result = notifier.showOrUpdate(model())

        assertTrue(result !is NotificationPostResult.Failed)
        assertEquals(NotificationPostResult.NotificationsDisabled, result)
    }

    @Test
    fun `a disabled Blick channel is never recreated or modified by showOrUpdate`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )

        notifier.showOrUpdate(model())

        val channelAfter = manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channelAfter.importance)
        assertEquals(1, manager.notificationChannels.size)
    }

    @Test
    fun `an unexpected construction failure returns Failed without throwing`() {
        val failingBuilder = mockk<RoutineNotificationBuilder>()
        every { failingBuilder.build(any()) } throws RuntimeException("boom")
        val notifierWithFailingBuilder = AndroidRoutineNotifier(context, failingBuilder, availabilityChecker)

        val result = notifierWithFailingBuilder.showOrUpdate(model())

        assertEquals(NotificationPostResult.Failed, result)
        assertEquals(0, manager.activeNotifications.size)
    }
}

/**
 * Covers [AndroidNotificationAvailabilityChecker] directly — the one shared source of truth
 * [AndroidRoutineNotifier], `RoutineActiveWindowWorker`, and the routine details screen all
 * read through (see [NotificationAvailability]'s class doc) — distinguishing all four states
 * against the real framework [NotificationManager] rather than a hand-rolled duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidNotificationAvailabilityCheckerTest {

    private val application: android.app.Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val checker = AndroidNotificationAvailabilityChecker(context)

    // See AndroidRoutineNotifierTest's identical @Before for why this is needed: only the two
    // tests explicitly about the runtime permission itself (below) should ever see it missing.
    @Before
    fun grantNotificationPermission() {
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `nothing blocking -- Available`() {
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `no channel yet and notifications enabled -- still Available`() {
        // A missing channel is not ChannelDisabled -- the normal channel-creation path must
        // still be allowed to run the first time a notification is actually built (see
        // NotificationAvailability.Available's own doc).
        assertEquals(null, manager.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID))
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `app-wide notifications disabled -- AppDisabled`() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationAvailability.AppDisabled, checker.check())
    }

    @Test
    fun `Blick channel specifically disabled -- ChannelDisabled`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )
        assertEquals(NotificationAvailability.ChannelDisabled, checker.check())
    }

    @Test
    fun `Blick channel exists and enabled -- Available`() {
        manager.createNotificationChannel(
            NotificationChannel(RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_LOW),
        )
        assertEquals(NotificationAvailability.Available, checker.check())
    }

    @Test
    fun `runtime permission missing on API 33+ -- PermissionMissing, checked before the app-wide toggle`() {
        shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Even with the app-wide toggle enabled, the missing runtime permission on API 33+
        // must be reported first -- it is the more specific, more actionable reason.
        assertEquals(NotificationAvailability.PermissionMissing, checker.check())
    }

    @Test
    fun `runtime permission granted on API 33+ and everything else fine -- Available`() {
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationAvailability.Available, checker.check())
    }
}
