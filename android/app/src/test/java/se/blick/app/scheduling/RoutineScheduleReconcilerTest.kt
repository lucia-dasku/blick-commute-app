package se.blick.app.scheduling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Plain JVM tests (fakes only, no WorkManager/Robolectric needed) for [RoutineScheduleReconciler]
 * — the reconciliation pass [se.blick.app.BlickApplication] runs at process start and again on
 * every `ACTION_TIMEZONE_CHANGED` broadcast (see that class's own doc).
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

    private class RecordingScheduler : RoutineScheduler {
        val scheduled = mutableListOf<String>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduled += routine.id
        }
        override fun cancelActivation(routineId: String) = Unit
    }

    /** Records every call — for proving `reconcileAll` also reconciles the widget on every run
     * (process start, timezone change, and reboot via `BootCompletedReceiver`, all of which call
     * this one function — see [RoutineScheduleReconciler]'s own doc). */
    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = Unit
        override suspend fun clear() = Unit
        override suspend fun reconcile() {
            reconcileCallCount++
        }
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
}
