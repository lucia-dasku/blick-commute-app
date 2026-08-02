package se.blick.app.notification

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Plain JVM tests (fakes only, no Robolectric/Android needed) for [StopRoutineNotificationAction]
 * — the effect behind the ongoing notification's Stop action, kept separate from
 * [StopRoutineNotificationReceiver] specifically so it's testable this way (see
 * `RoutineScheduleReconcilerTest` for the same split behind `BootCompletedReceiver`).
 */
class StopRoutineNotificationActionTest {

    private fun routine(id: String = "r1") = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private class FakeRoutineRepository(private var routine: CommuteRoutine?) : RoutineRepository {
        var lastPaused: Pair<String, LocalDate>? = null
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getById(id: String): CommuteRoutine? = routine?.takeIf { it.id == id }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            lastPaused = id to date
            routine = routine?.copy(pausedDate = date)
        }
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = routine != null
    }

    private class RecordingScheduler : RoutineScheduler {
        val scheduled = mutableListOf<CommuteRoutine>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduled += routine
        }
        override fun cancelActivation(routineId: String) = Unit
    }

    private class RecordingNotifier : RoutineNotifier {
        var removeCallCount = 0
        override fun showOrUpdate(model: RoutineNotificationModel): NotificationPostResult = throw NotImplementedError()
        override fun remove() {
            removeCallCount++
        }
    }

    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        var clearCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = throw NotImplementedError()
        override suspend fun clear() {
            clearCallCount++
        }
        override suspend fun reconcile() {
            reconcileCallCount++
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = throw NotImplementedError()
    }

    // 2026-07-31T22:30:00Z is 2026-08-01 in Stockholm's summer UTC+2 offset -- deliberately
    // chosen to prove "today" is resolved in the device's own zone, not the clock's UTC instant.
    private val fixedInstant = Instant.parse("2026-07-31T22:30:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val stockholmZone = DeviceZoneProvider { ZoneId.of("Europe/Stockholm") }

    @Test
    fun `stop pauses the routine for today in the device's own zone, reschedules, removes the notification, and reconciles the widget`() = runTest {
        val repository = FakeRoutineRepository(routine())
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        StopRoutineNotificationAction(repository, scheduler, notifier, widgetUpdater, clock, stockholmZone).stop("r1")

        assertEquals("r1" to LocalDate.of(2026, 8, 1), repository.lastPaused)
        assertEquals(listOf(LocalDate.of(2026, 8, 1)), scheduler.scheduled.map { it.pausedDate })
        assertEquals(1, notifier.removeCallCount)
        // reconcile(), not clear() -- proven indirectly by clearCallCount staying at zero, since
        // GlanceRoutineWidgetUpdater has no other way to distinguish them from this test's fake.
        assertEquals(1, widgetUpdater.reconcileCallCount)
        assertEquals(0, widgetUpdater.clearCallCount)
    }

    @Test
    fun `stop removes the notification and still reconciles the widget even when the routine has already been deleted`() = runTest {
        val repository = FakeRoutineRepository(routine = null)
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        StopRoutineNotificationAction(repository, scheduler, notifier, widgetUpdater, clock, stockholmZone).stop("gone")

        assertNull(repository.lastPaused)
        assertEquals(emptyList<CommuteRoutine>(), scheduler.scheduled)
        assertEquals(1, notifier.removeCallCount)
        // reconcile() (not a null-check branch) correctly handles "already deleted" too -- see
        // RoutineWidgetUpdater.reconcile's own doc.
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }
}
