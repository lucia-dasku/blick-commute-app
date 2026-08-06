package se.blick.app.scheduling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.RoutineDurationValidationResult
import se.blick.app.domain.usecase.RoutineDurationValidator
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Plain JVM tests (fakes only, no WorkManager/Robolectric needed) for [RoutineScheduleReconciler]
 * — the reconciliation pass [NotificationRecoveryCoordinator.onTimeZoneChanged] runs on every
 * `ACTION_TIMEZONE_CHANGED` broadcast (see that class's own doc; process start and foreground
 * instead go through that same coordinator's own [NotificationRecoveryCoordinator.onAppStart]/
 * [NotificationRecoveryCoordinator.onForeground], which do not use this reconciler at all).
 */
class RoutineScheduleReconcilerTest {

    private fun routine(id: String, enabled: Boolean) = CommuteRoutine(
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
        enabled = enabled,
    )

    private class FakeRoutineRepository(private val routines: List<CommuteRoutine>) : RoutineRepository {
        private val state = MutableStateFlow(routines)
        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = routines.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: LocalDate) = throw NotImplementedError()
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = routines.isNotEmpty()
    }

    /** [throwForId] simulates a single routine's [scheduleActivation] failing — a corrupted
     * record, or a WorkManager call throwing on some OEM — to prove `reconcileAll` recovers
     * (logs and continues) rather than letting it abort the whole reconcile pass. */
    private class RecordingScheduler(private val throwForId: String? = null) : RoutineScheduler {
        val scheduled = mutableListOf<String>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            if (routine.id == throwForId) throw RuntimeException("scheduling failed for ${routine.id}")
            scheduled += routine.id
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    /** Records every call — for proving `reconcileAll` also reconciles the widget on every run
     * (invoked, today, only for a device-timezone change, via
     * `NotificationRecoveryCoordinator.onTimeZoneChanged` — see [RoutineScheduleReconciler]'s own
     * doc). */
    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = Unit
        override suspend fun clear() = Unit
        override suspend fun reconcile() {
            reconcileCallCount++
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    /** Throws from every method — proves `reconcileAll` wraps its [RoutineWidgetUpdater] call
     * with `runWidgetUpdateSafely` rather than letting a widget/Glance/DataStore failure make
     * the scheduling loop above look like it failed too, even though every routine was already
     * genuinely scheduled by the time this runs. */
    private class FailingWidgetUpdater : RoutineWidgetUpdater {
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = Unit
        override suspend fun clear() = Unit
        override suspend fun reconcile(): Unit = throw RuntimeException("widget update failed")
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    @Test
    fun `reconcileAll schedules only enabled routines`() = runTest {
        val repository = FakeRoutineRepository(
            listOf(routine("r1", enabled = true), routine("r2", enabled = false), routine("r3", enabled = true)),
        )
        val scheduler = RecordingScheduler()

        RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater()).reconcileAll()

        assertEquals(listOf("r1", "r3"), scheduler.scheduled)
    }

    @Test
    fun `reconcileAll with no saved routines schedules nothing`() = runTest {
        val repository = FakeRoutineRepository(emptyList())
        val scheduler = RecordingScheduler()

        RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater()).reconcileAll()

        assertEquals(emptyList<String>(), scheduler.scheduled)
    }

    @Test
    fun `reconcileAll can be called repeatedly -- idempotent from this class's perspective`() = runTest {
        // scheduleActivation's own idempotency (REPLACE semantics) is covered by
        // WorkManagerRoutineSchedulerTest -- this only proves the reconciler calls it once per
        // enabled routine, every time it runs, which is what makes repeated calls (process
        // start, then again on a timezone change) safe.
        val repository = FakeRoutineRepository(listOf(routine("r1", enabled = true)))
        val scheduler = RecordingScheduler()
        val reconciler = RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater())

        reconciler.reconcileAll()
        reconciler.reconcileAll()

        assertEquals(listOf("r1", "r1"), scheduler.scheduled)
    }

    @Test
    fun `reconcileAll also reconciles the widget every time it runs`() = runTest {
        val repository = FakeRoutineRepository(listOf(routine("r1", enabled = true)))
        val widgetUpdater = RecordingWidgetUpdater()
        val reconciler = RoutineScheduleReconciler(repository, RecordingScheduler(), widgetUpdater)

        reconciler.reconcileAll()
        reconciler.reconcileAll()

        assertEquals(2, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `reconcileAll still schedules every enabled routine when the widget updater throws`() = runTest {
        val repository = FakeRoutineRepository(
            listOf(routine("r1", enabled = true), routine("r2", enabled = false), routine("r3", enabled = true)),
        )
        val scheduler = RecordingScheduler()

        // If the widget failure below were left unwrapped, reconcileAll() would throw and this
        // assertion would never run -- even though scheduling every enabled routine above
        // already genuinely completed.
        RoutineScheduleReconciler(repository, scheduler, FailingWidgetUpdater()).reconcileAll()

        assertEquals(listOf("r1", "r3"), scheduler.scheduled)
    }

    @Test
    fun `one routine's scheduling failure does not prevent the rest from being scheduled`() = runTest {
        // r2 throws; r1 comes before it and r3 comes after it in iteration order -- proving
        // this isn't just "the first failure is swallowed" but that the forEach genuinely
        // continues past a mid-batch failure rather than aborting there.
        val repository = FakeRoutineRepository(
            listOf(routine("r1", enabled = true), routine("r2", enabled = true), routine("r3", enabled = true)),
        )
        val scheduler = RecordingScheduler(throwForId = "r2")

        val result = kotlin.runCatching {
            RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater()).reconcileAll()
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf("r1", "r3"), scheduler.scheduled)
    }

    /** Mirrors [WorkManagerRoutineScheduler]'s own defensive duration re-check using the SAME
     * shared [RoutineDurationValidator], without needing the Robolectric/WorkManager harness
     * `WorkManagerRoutineSchedulerTest` already covers that concrete implementation with —
     * proves `reconcileAll` behaves correctly when the scheduler it calls silently declines to
     * schedule an over-limit routine (no exception, unlike [RecordingScheduler]'s `throwForId`),
     * exactly like the real implementation does. */
    private class DurationValidatingScheduler : RoutineScheduler {
        val scheduled = mutableListOf<String>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            val validation = RoutineDurationValidator.validateSelf(routine)
            if (validation is RoutineDurationValidationResult.ExceedsDailyLimit) return
            scheduled += routine.id
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    @Test
    fun `an over-limit routine is silently skipped, without preventing other routines from being reconciled`() = runTest {
        val validBefore = routine("r1", enabled = true) // default 07:00-09:00, 2h
        val overLimit = routine("r2", enabled = true).copy(startTime = LocalTime.of(6, 0), endTime = LocalTime.of(13, 0)) // 7h
        val validAfter = routine("r3", enabled = true)
        val repository = FakeRoutineRepository(listOf(validBefore, overLimit, validAfter))
        val scheduler = DurationValidatingScheduler()

        RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater()).reconcileAll()

        // r2 is silently dropped by the scheduler itself; r1 and r3 (before and after it in
        // iteration order) are scheduled exactly as if r2 were never there.
        assertEquals(listOf("r1", "r3"), scheduler.scheduled)
    }

    @Test
    fun `a routine list read failure is handled -- no crash, widget still reconciled`() = runTest {
        val repository = object : RoutineRepository {
            override fun observeAll(): Flow<List<CommuteRoutine>> = kotlinx.coroutines.flow.flow {
                throw RuntimeException("Room read failed")
            }
            override suspend fun getById(id: String): CommuteRoutine? = throw NotImplementedError()
            override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
            override suspend fun delete(id: String) = throw NotImplementedError()
            override suspend fun pauseForDate(id: String, date: LocalDate) = throw NotImplementedError()
            override suspend fun clearPause(id: String) = throw NotImplementedError()
            override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
            override suspend fun hasAnyRoutine(): Boolean = throw NotImplementedError()
        }
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()

        val result = kotlin.runCatching {
            RoutineScheduleReconciler(repository, scheduler, widgetUpdater).reconcileAll()
        }

        assertTrue(result.isSuccess)
        assertEquals(emptyList<String>(), scheduler.scheduled)
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }
}
