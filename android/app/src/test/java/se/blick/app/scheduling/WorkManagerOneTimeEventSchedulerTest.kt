package se.blick.app.scheduling

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
