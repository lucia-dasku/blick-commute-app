package se.blick.app.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * `NotificationRecoveryCoordinatorTest` for the same split behind `BlickApplication`'s own
 * receivers).
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
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    /** Always throws [error] from [scheduleActivation] — for proving a scheduling failure AFTER
     * a successful pause must never prevent the notification from being removed (persistence
     * and scheduling are two different results — see [StopRoutineNotificationAction]'s own
     * class doc). */
    private class FailingScheduler(private val error: Throwable) : RoutineScheduler {
        var scheduleCallCount = 0
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduleCallCount++
            throw error
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    /** Configurable per-method failure — for proving a [getById]/[pauseForDate] failure must
     * never let [stop] pretend the routine was paused, and must never remove a notification
     * that may still correctly represent an active window. */
    private class ConfigurableFailingRoutineRepository(
        private var routine: CommuteRoutine?,
        private val failGetById: Throwable? = null,
        private val failPauseForDate: Throwable? = null,
    ) : RoutineRepository {
        var pauseForDateCallCount = 0
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getById(id: String): CommuteRoutine? {
            failGetById?.let { throw it }
            return routine?.takeIf { it.id == id }
        }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            pauseForDateCallCount++
            failPauseForDate?.let { throw it }
            routine = routine?.copy(pausedDate = date)
        }
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = routine != null
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

    /** Throws from every method — proves stop() wraps its [RoutineWidgetUpdater] call with
     * `runWidgetUpdateSafely` rather than letting a widget/Glance/DataStore failure propagate
     * out of stop() itself, even though the pause/reschedule/notification-removal above already
     * genuinely succeeded (see runWidgetUpdateSafely's own doc, and
     * StopRoutineNotificationReceiver's own doc on why an uncaught exception here would
     * otherwise crash the app). */
    private class FailingWidgetUpdater : RoutineWidgetUpdater {
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = throw NotImplementedError()
        override suspend fun clear() = throw RuntimeException("widget update failed")
        override suspend fun reconcile() = throw RuntimeException("widget update failed")
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

    @Test
    fun `stop still pauses, reschedules, and removes the notification when the widget updater throws`() = runTest {
        val repository = FakeRoutineRepository(routine())
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()

        // If the widget failure below were left unwrapped, stop() would throw and none of the
        // assertions below would even run.
        StopRoutineNotificationAction(repository, scheduler, notifier, FailingWidgetUpdater(), clock, stockholmZone).stop("r1")

        assertEquals("r1" to LocalDate.of(2026, 8, 1), repository.lastPaused)
        assertEquals(listOf(LocalDate.of(2026, 8, 1)), scheduler.scheduled.map { it.pausedDate })
        assertEquals(1, notifier.removeCallCount)
    }

    // ---- Persistence vs. scheduling: once the pause succeeds, the notification is removed
    // regardless of whether the follow-up reschedule succeeds; a failure to even pause must
    // never remove a notification that may still correctly represent an active window ----

    @Test
    fun `stop still removes the notification and leaves the routine paused when rescheduling throws`() = runTest {
        val repository = FakeRoutineRepository(routine())
        val scheduler = FailingScheduler(RuntimeException("scheduling failed"))
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        // If the scheduling failure below were left unwrapped, stop() would throw and none of
        // the assertions below would even run.
        StopRoutineNotificationAction(repository, scheduler, notifier, widgetUpdater, clock, stockholmZone).stop("r1")

        // The pause already genuinely succeeded in Room -- the notification must still be
        // removed even though the reschedule attempt on top of it failed.
        assertEquals("r1" to LocalDate.of(2026, 8, 1), repository.lastPaused)
        assertEquals(1, scheduler.scheduleCallCount)
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `stop does not remove the notification when pauseForDate itself fails`() = runTest {
        val repository = ConfigurableFailingRoutineRepository(routine(), failPauseForDate = RuntimeException("Room unavailable"))
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        StopRoutineNotificationAction(repository, scheduler, notifier, widgetUpdater, clock, stockholmZone).stop("r1")

        // The pause did NOT happen -- stop() must not pretend it did, and must not remove a
        // notification that may still correctly represent an active window.
        assertEquals(1, repository.pauseForDateCallCount)
        assertEquals(emptyList<CommuteRoutine>(), scheduler.scheduled)
        assertEquals(0, notifier.removeCallCount)
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `stop does not remove the notification when reading the routine fails`() = runTest {
        val repository = ConfigurableFailingRoutineRepository(routine(), failGetById = RuntimeException("Room unavailable"))
        val scheduler = RecordingScheduler()
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        StopRoutineNotificationAction(repository, scheduler, notifier, widgetUpdater, clock, stockholmZone).stop("r1")

        assertEquals(0, repository.pauseForDateCallCount)
        assertEquals(0, notifier.removeCallCount)
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    // ---- Coroutine cancellation is preserved at every step (a genuine CancellationException
    // must always propagate unconverted, never be treated as an ordinary failure) ----

    @Test
    fun `a cancellation while reading the routine propagates instead of being treated as an ordinary failure`() = runTest {
        val repository = ConfigurableFailingRoutineRepository(routine(), failGetById = CancellationException("test cancellation"))
        val notifier = RecordingNotifier()

        var caught: CancellationException? = null
        try {
            StopRoutineNotificationAction(repository, RecordingScheduler(), notifier, RecordingWidgetUpdater(), clock, stockholmZone).stop("r1")
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("expected stop() to rethrow a CancellationException, but it was swallowed", caught != null)
        assertEquals(0, notifier.removeCallCount)
    }

    @Test
    fun `a cancellation while pausing the routine propagates instead of being treated as an ordinary failure`() = runTest {
        val repository = ConfigurableFailingRoutineRepository(routine(), failPauseForDate = CancellationException("test cancellation"))
        val notifier = RecordingNotifier()

        var caught: CancellationException? = null
        try {
            StopRoutineNotificationAction(repository, RecordingScheduler(), notifier, RecordingWidgetUpdater(), clock, stockholmZone).stop("r1")
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("expected stop() to rethrow a CancellationException, but it was swallowed", caught != null)
        assertEquals(0, notifier.removeCallCount)
    }

    @Test
    fun `a cancellation while rescheduling propagates instead of being treated as an ordinary failure`() = runTest {
        val repository = FakeRoutineRepository(routine())
        val scheduler = FailingScheduler(CancellationException("test cancellation"))
        val notifier = RecordingNotifier()

        var caught: CancellationException? = null
        try {
            StopRoutineNotificationAction(repository, scheduler, notifier, RecordingWidgetUpdater(), clock, stockholmZone).stop("r1")
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("expected stop() to rethrow a CancellationException, but it was swallowed", caught != null)
        // The pause itself genuinely succeeded in Room -- only the propagated cancellation
        // (correctly, since a genuine cancellation tears down the whole operation) is why
        // remove() was never reached, not a bug that skips it under ordinary circumstances.
        assertEquals("r1" to LocalDate.of(2026, 8, 1), repository.lastPaused)
        assertEquals(0, notifier.removeCallCount)
    }
}
