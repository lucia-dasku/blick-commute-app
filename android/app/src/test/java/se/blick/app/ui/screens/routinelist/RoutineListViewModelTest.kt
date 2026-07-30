package se.blick.app.ui.screens.routinelist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.scheduling.RoutineScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleRoutine(id: String = "r1", name: String = "Morning commute") = CommuteRoutine(
        id = id,
        name = name,
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        startTime = LocalTime.of(7, 30),
        endTime = LocalTime.of(8, 0),
    )

    private class FakeRoutineRepository(initial: List<CommuteRoutine>) : RoutineRepository {
        private val state = MutableStateFlow(initial)
        val deletedIds = mutableListOf<String>()
        val pausedIds = mutableListOf<Pair<String, LocalDate>>()

        override fun observeAll(): Flow<List<CommuteRoutine>> = state

        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }

        override suspend fun save(routine: CommuteRoutine) {
            state.value = state.value.filterNot { it.id == routine.id } + routine
        }

        override suspend fun delete(id: String) {
            deletedIds += id
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun pauseForDate(id: String, date: LocalDate) {
            pausedIds += id to date
            state.value = state.value.map { if (it.id == id) it.copy(pausedDate = date) else it }
        }

        override suspend fun clearPause(id: String) {
            state.value = state.value.map { if (it.id == id) it.copy(pausedDate = null) else it }
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override suspend fun hasAnyRoutine(): Boolean = state.value.isNotEmpty()
    }

    /** Records every cancellation call — for proving deleteRoutine also cancels the deleted
     * routine's scheduled activation, not just its stored data. */
    private class FakeRoutineScheduler : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        val cancelledRoutineIds = mutableListOf<String>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) {
            cancelledRoutineIds += routineId
        }
    }

    @Test
    fun `starts in loading state before the repository emits`() = runTest(dispatcher) {
        val repository = FakeRoutineRepository(listOf(sampleRoutine()))
        val viewModel = RoutineListViewModel(repository, FakeRoutineScheduler())

        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `reflects routines from the repository once collected`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = RoutineListViewModel(repository, FakeRoutineScheduler())

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(listOf(routine), viewModel.uiState.value.routines)
    }

    @Test
    fun `deleteRoutine delegates to the repository and the list updates`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = RoutineListViewModel(repository, FakeRoutineScheduler())
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteRoutine(routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(routine.id), repository.deletedIds)
        assertTrue(viewModel.uiState.value.routines.isEmpty())
    }

    @Test
    fun `deleteRoutine also cancels the routine's scheduled activation`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = FakeRoutineScheduler()
        val viewModel = RoutineListViewModel(repository, scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteRoutine(routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(routine.id), scheduler.cancelledRoutineIds)
    }

    @Test
    fun `pauseForToday records today's date against the routine id`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = RoutineListViewModel(repository, FakeRoutineScheduler())
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.pauseForToday(routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(routine.id, repository.pausedIds.single().first)
        assertEquals(LocalDate.now(), repository.pausedIds.single().second)
    }
}
