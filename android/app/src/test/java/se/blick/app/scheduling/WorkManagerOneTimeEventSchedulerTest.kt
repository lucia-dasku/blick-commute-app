package se.blick.app.scheduling

import android.app.Notification
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WorkManagerOneTimeEventSchedulerTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerOneTimeEventScheduler

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerOneTimeEventScheduler(context, clock)
    }

    @Test fun `future event schedules one unique reminder without a periodic loop`() {
        scheduler.schedule(event())
        val infos = workManager.getWorkInfosForUniqueWork(
            WorkManagerOneTimeEventScheduler.uniqueWorkName("event"),
        ).get()
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(infos.single().tags.contains(WorkManagerOneTimeEventScheduler.WORK_TAG))
    }

    @Test fun `editing replaces existing event work`() {
        scheduler.schedule(event())
        scheduler.schedule(event().copy(date = LocalDate.of(2026, 9, 18)))
        val infos = workManager.getWorkInfosForUniqueWork(
            WorkManagerOneTimeEventScheduler.uniqueWorkName("event"),
        ).get()
        assertEquals(1, infos.count { it.state != WorkInfo.State.CANCELLED })
    }

    @Test fun `past event leaves no scheduled work`() {
        scheduler.schedule(event().copy(date = LocalDate.of(2026, 8, 31)))
        val infos = workManager.getWorkInfosForUniqueWork(
            WorkManagerOneTimeEventScheduler.uniqueWorkName("event"),
        ).get()
        assertTrue(infos.none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun `reminder is ordinary auto cancel and opens the exact event without journey data`() {
        val context = RuntimeEnvironment.getApplication()
        val notification = buildOneTimeEventReminderNotification(context, event())

        assertEquals("Concert today", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "Your travel plan is ready. Tap to view.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(androidx.core.app.NotificationCompat.CATEGORY_REMINDER, notification.category)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(
            setOf(OneTimeEventReminderNavigation.EXTRA_ONE_TIME_EVENT_ID),
            intent.extras?.keySet(),
        )
        assertEquals("event", OneTimeEventReminderNavigation.consumeEventId(intent))
        assertNull(OneTimeEventReminderNavigation.consumeEventId(intent))
    }

    @Test fun `separate reminder intents support cold and warm delivery independently`() {
        val cold = OneTimeEventReminderNavigation.putEventId(Intent(), "cold-event")
        val warm = OneTimeEventReminderNavigation.putEventId(Intent(), "warm-event")

        assertEquals("cold-event", OneTimeEventReminderNavigation.consumeEventId(cold))
        assertEquals("warm-event", OneTimeEventReminderNavigation.consumeEventId(warm))
    }

    private fun event() = OneTimeEvent(
        id = "event",
        label = OneTimeEventLabel.EVENT,
        name = "Concert",
        originId = "A",
        originName = "Home",
        destinationId = "B",
        destinationName = "Globen",
        date = LocalDate.of(2026, 9, 17),
        time = LocalTime.of(18, 30),
    )
}
