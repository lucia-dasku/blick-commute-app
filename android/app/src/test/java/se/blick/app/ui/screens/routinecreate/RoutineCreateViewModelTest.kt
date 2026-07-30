package se.blick.app.ui.screens.routinecreate

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.blick.app.data.repository.DirectionOption
import se.blick.app.data.repository.DirectionOptionsSource
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StopRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import se.blick.app.ui.navigation.Routes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineCreateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fruangen = Site(siteId = 9145, name = "Fruängen", note = null, lat = 59.28, lon = 17.96, stopAreaIds = listOf(9145))
    private val slussen = Site(siteId = 9192, name = "Slussen", note = null, lat = 59.32, lon = 18.07, stopAreaIds = listOf(9192))
    private val busOption = DirectionOption(
        lineId = 705,
        lineDesignation = "705",
        transportMode = TransportMode.BUS,
        directionCode = 1,
        destinationLabel = "Segeltorp",
    )
    private val metroOption = DirectionOption(
        lineId = 14,
        lineDesignation = "14",
        transportMode = TransportMode.METRO,
        directionCode = 2,
        destinationLabel = "T-Centralen",
    )

    // ---- StopRepository fakes ----

    private class FakeStopRepository(private val sitesByQuery: Map<String, List<Site>> = emptyMap()) : StopRepository {
        var lastQuery: String? = null
        override suspend fun searchStops(query: String): List<Site> {
            lastQuery = query
            return sitesByQuery[query] ?: emptyList()
        }
    }

    /** Always throws — for testing the pure failure path. */
    private class FailingStopRepository(private val message: String) : StopRepository {
        override suspend fun searchStops(query: String): List<Site> = throw RuntimeException(message)
    }

    /** Always throws a real [CancellationException] — must propagate, not become searchFailed. */
    private class CancellingStopRepository : StopRepository {
        override suspend fun searchStops(query: String): List<Site> = throw CancellationException("test cancellation")
    }

    /** Fails while [shouldFail] is true, succeeds with [resultsByQuery] once flipped off —
     * for testing "failed, then retried successfully" flows without a second fake. */
    private class ToggleableStopRepository(
        var shouldFail: Boolean,
        private val resultsByQuery: Map<String, List<Site>> = emptyMap(),
    ) : StopRepository {
        var callCount = 0
        override suspend fun searchStops(query: String): List<Site> {
            callCount++
            if (shouldFail) throw RuntimeException("boom")
            return resultsByQuery[query] ?: emptyList()
        }
    }

    // ---- DirectionOptionsSource fakes ----

    private class FakeDirectionOptionsSource(
        private val optionsBySite: Map<Long, List<DirectionOption>> = emptyMap(),
    ) : DirectionOptionsSource {
        var callCount = 0
        override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> {
            callCount++
            return optionsBySite[siteId] ?: emptyList()
        }
    }

    /** Always throws — a real backend/network failure, distinct from a legitimately empty result. */
    private class FailingDirectionOptionsSource : DirectionOptionsSource {
        override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> = throw RuntimeException("boom")
    }

    /** Always throws a real [CancellationException] — must propagate, not become directionsFailed. */
    private class CancellingDirectionOptionsSource : DirectionOptionsSource {
        override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> =
            throw CancellationException("test cancellation")
    }

    /**
     * Each call suspends on its own [CompletableDeferred], indexed by call order (not by
     * siteId — a retry of the SAME site must get its own independent, separately
     * controllable slot). Lets tests resolve calls out of order to reproduce a slow
     * request completing after a newer one has already superseded it.
     */
    private class ControllableDirectionOptionsSource : DirectionOptionsSource {
        private val pending = mutableListOf<CompletableDeferred<List<DirectionOption>>>()
        val callCount: Int get() = pending.size

        override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> {
            val deferred = CompletableDeferred<List<DirectionOption>>()
            pending += deferred
            return deferred.await()
        }

        /** Resolves the call at [callIndex] (0-indexed, in call order) successfully. */
        fun complete(callIndex: Int, result: List<DirectionOption>) {
            pending[callIndex].complete(result)
        }

        fun completeWithError(callIndex: Int, error: Throwable) {
            pending[callIndex].completeExceptionally(error)
        }
    }

    // ---- RoutineRepository fakes ----

    private class FakeRoutineRepository(initial: List<CommuteRoutine> = emptyList()) : RoutineRepository {
        val saved = mutableListOf<CommuteRoutine>()
        private val state = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            saved += routine
            state.value = state.value.filterNot { it.id == routine.id } + routine
        }
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = state.value.isNotEmpty()
    }

    /** Always throws on save — for testing the pure save-failure path. */
    private class FailingRoutineRepository : RoutineRepository {
        var saveCallCount = 0
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: String): CommuteRoutine? = null
        override suspend fun save(routine: CommuteRoutine) {
            saveCallCount++
            throw RuntimeException("save failed")
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = false
    }

    /** Always throws a real [CancellationException] from save — must propagate, not become saveFailed. */
    private class CancellingRoutineRepository : RoutineRepository {
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: String): CommuteRoutine? = null
        override suspend fun save(routine: CommuteRoutine): Unit = throw CancellationException("test cancellation")
        override suspend fun delete(id: String) = Unit
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = false
    }

    /** Fails while [shouldFail] is true, succeeds once flipped off — for "failed, then retried
     * successfully" save flows. */
    private class ToggleableRoutineRepository(var shouldFail: Boolean) : RoutineRepository {
        val saved = mutableListOf<CommuteRoutine>()
        private val state = MutableStateFlow<List<CommuteRoutine>>(emptyList())
        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            if (shouldFail) throw RuntimeException("boom")
            saved += routine
            state.value = state.value + routine
        }
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = false
    }

    /** Suspends on [save] until [release] is called — for proving overlapping save() calls
     * only persist once. */
    private class SlowRoutineRepository : RoutineRepository {
        val saved = mutableListOf<CommuteRoutine>()
        var callCount = 0
        private val state = MutableStateFlow<List<CommuteRoutine>>(emptyList())
        private val gate = CompletableDeferred<Unit>()
        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = null
        override suspend fun save(routine: CommuteRoutine) {
            callCount++
            gate.await()
            saved += routine
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = false
        fun release() {
            gate.complete(Unit)
        }
    }

    private fun viewModel(
        stops: StopRepository = FakeStopRepository(mapOf("Fru" to listOf(fruangen))),
        directions: DirectionOptionsSource = FakeDirectionOptionsSource(mapOf(9145L to listOf(busOption, metroOption))),
        routines: RoutineRepository = FakeRoutineRepository(),
        routineId: String? = null,
    ) = RoutineCreateViewModel(
        SavedStateHandle(routineId?.let { mapOf(Routes.RoutineEdit.ARG_ROUTINE_ID to it) } ?: emptyMap()),
        stops,
        directions,
        routines,
    )

    /** Selects [fruangen], advances past its (successful, non-empty) direction lookup, sets
     * a valid direction/schedule so [RoutineCreateUiState.canSave] is true. Shared setup for
     * the save()-focused tests. */
    private fun RoutineCreateViewModel.advanceToSaveReady() {
        selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        selectTransportMode(TransportMode.METRO)
        selectDirection(metroOption)
        toggleDay(DayOfWeek.MONDAY)
        setStartTime(LocalTime.of(7, 0))
        setEndTime(LocalTime.of(9, 0))
    }

    // ---- Stop search ----

    @Test
    fun `starts on the stop step with no results`() = runTest(dispatcher) {
        val vm = viewModel()

        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertTrue(vm.uiState.value.siteResults.isEmpty())
    }

    @Test
    fun `search is debounced and populates results after settling`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSiteQueryChanged("Fru")
        assertTrue(vm.uiState.value.siteResults.isEmpty())

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(fruangen), vm.uiState.value.siteResults)
    }

    @Test
    fun `a failed search surfaces a failure flag instead of an empty result`() = runTest(dispatcher) {
        val vm = viewModel(stops = FailingStopRepository("Unable to resolve host"))

        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.siteResults.isEmpty())
        assertTrue(vm.uiState.value.searchFailed)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `a cancellation while searching is not converted into searchFailed`() = runTest(dispatcher) {
        val vm = viewModel(stops = CancellingStopRepository())

        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.searchFailed)
    }

    @Test
    fun `retryStopSearch retries the same query and can succeed after a prior failure`() = runTest(dispatcher) {
        val stops = ToggleableStopRepository(shouldFail = true, resultsByQuery = mapOf("Fru" to listOf(fruangen)))
        val vm = viewModel(stops = stops)

        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.searchFailed)
        assertTrue(vm.uiState.value.siteResults.isEmpty())
        assertEquals(1, stops.callCount)

        stops.shouldFail = false
        vm.retryStopSearch()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.searchFailed)
        assertEquals(listOf(fruangen), vm.uiState.value.siteResults)
        assertEquals(2, stops.callCount)
    }

    @Test
    fun `a later successful search clears a previous error`() = runTest(dispatcher) {
        // Regression: this used to "pass" by only clearing the query to blank, which never
        // exercises the repository at all and proves nothing about real recovery.
        val stops = ToggleableStopRepository(shouldFail = true, resultsByQuery = mapOf("Centralen" to listOf(fruangen)))
        val vm = viewModel(stops = stops)

        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.searchFailed)

        stops.shouldFail = false
        vm.onSiteQueryChanged("Centralen")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.searchFailed)
        assertEquals(listOf(fruangen), vm.uiState.value.siteResults)
        assertEquals(2, stops.callCount)
    }

    // ---- New query resets stale selection/direction state ----

    @Test
    fun `changing the query after a direction failure clears the stale direction state`() = runTest(dispatcher) {
        val vm = viewModel(directions = FailingDirectionOptionsSource())

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.directionsFailed)

        vm.onSiteQueryChanged("New query")

        assertFalse(vm.uiState.value.directionsFailed)
        assertFalse(vm.uiState.value.directionsEmpty)
        assertFalse(vm.uiState.value.isLoadingDirections)
        assertEquals(null, vm.uiState.value.selectedSite)
    }

    // ---- Direction loading ----

    @Test
    fun `selecting a site loads direction options and advances to the transport mode step`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(listOf(TransportMode.BUS, TransportMode.METRO), vm.uiState.value.availableTransportModes)
    }

    @Test
    fun `an exception loading directions sets directionsFailed, not directionsEmpty`() = runTest(dispatcher) {
        val vm = viewModel(directions = FailingDirectionOptionsSource())

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertTrue(vm.uiState.value.directionsFailed)
        assertFalse(vm.uiState.value.directionsEmpty)
    }

    @Test
    fun `a successful lookup with zero directions sets directionsEmpty, not directionsFailed`() = runTest(dispatcher) {
        val vm = viewModel(directions = FakeDirectionOptionsSource(emptyMap()))

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertTrue(vm.uiState.value.directionsEmpty)
        assertFalse(vm.uiState.value.directionsFailed)
    }

    @Test
    fun `a cancellation while loading directions is not converted into directionsFailed`() = runTest(dispatcher) {
        val vm = viewModel(directions = CancellingDirectionOptionsSource())

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.directionsFailed)
        assertFalse(vm.uiState.value.directionsEmpty)
    }

    // ---- Direction-request races (a slow, superseded lookup must never win) ----

    @Test
    fun `changing the query while directions are loading invalidates the in-flight request`() = runTest(dispatcher) {
        val directions = ControllableDirectionOptionsSource()
        val vm = viewModel(directions = directions)

        vm.selectSite(fruangen) // call index 0
        // Let the launched coroutine actually reach getDirectionOptions() and suspend on its
        // CompletableDeferred (StandardTestDispatcher never runs a launch{} body just because
        // it was created — without this, the job would be cancelled before it ever registered
        // itself in ControllableDirectionOptionsSource, and directions.complete(0, ...) below
        // would throw IndexOutOfBoundsException instead of exercising the race at all).
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, directions.callCount)
        assertTrue(vm.uiState.value.isLoadingDirections)
        assertEquals(fruangen.siteId, vm.uiState.value.selectedSite?.siteId)

        vm.onSiteQueryChanged("something else")
        dispatcher.scheduler.advanceUntilIdle() // let the cancellation actually propagate
        assertFalse(vm.uiState.value.isLoadingDirections)
        assertEquals(null, vm.uiState.value.selectedSite)

        // Stop A's request finally resolves late, with a real (non-empty) result.
        directions.complete(0, listOf(busOption, metroOption))
        dispatcher.scheduler.advanceUntilIdle()

        // Must not resurrect selectedSite/options or advance the step — the query change
        // already invalidated this request.
        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertEquals(null, vm.uiState.value.selectedSite)
        assertFalse(vm.uiState.value.isLoadingDirections)
        assertFalse(vm.uiState.value.directionsFailed)
        assertFalse(vm.uiState.value.directionsEmpty)
        assertTrue(vm.uiState.value.directionOptions.isEmpty())
    }

    @Test
    fun `selecting a second stop while the first is still loading ignores the first once it resolves`() = runTest(dispatcher) {
        val directions = ControllableDirectionOptionsSource()
        val vm = viewModel(directions = directions)

        vm.selectSite(fruangen) // call index 0 (Stop A), left pending
        dispatcher.scheduler.advanceUntilIdle() // let A reach getDirectionOptions() and suspend
        assertEquals(1, directions.callCount)

        vm.selectSite(slussen) // call index 1 (Stop B) — must cancel/invalidate A
        dispatcher.scheduler.advanceUntilIdle() // let A's cancellation propagate and B reach its own suspension
        assertEquals(2, directions.callCount)

        // B resolves first.
        directions.complete(1, listOf(metroOption))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(slussen.siteId, vm.uiState.value.selectedSite?.siteId)
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(listOf(metroOption), vm.uiState.value.directionOptions)

        // A resolves late, with a DIFFERENT (also non-empty) result — must be ignored.
        directions.complete(0, listOf(busOption))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(slussen.siteId, vm.uiState.value.selectedSite?.siteId)
        assertEquals(listOf(metroOption), vm.uiState.value.directionOptions)
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
    }

    @Test
    fun `retrying directions ignores the original request if it resolves after the retry`() = runTest(dispatcher) {
        val directions = ControllableDirectionOptionsSource()
        val vm = viewModel(directions = directions)

        vm.selectSite(fruangen) // call index 0 (original), left pending/slow
        dispatcher.scheduler.advanceUntilIdle() // let it reach getDirectionOptions() and suspend
        assertEquals(1, directions.callCount)
        assertTrue(vm.uiState.value.isLoadingDirections)

        vm.retryDirections() // call index 1 (retry) — must cancel/invalidate index 0
        dispatcher.scheduler.advanceUntilIdle() // let the original's cancellation propagate and the retry reach its own suspension
        assertEquals(2, directions.callCount)

        // The retry succeeds.
        directions.complete(1, listOf(metroOption))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(listOf(metroOption), vm.uiState.value.directionOptions)

        // The ORIGINAL request finally resolves late with a failure — must not overwrite
        // the retry's already-applied success.
        directions.completeWithError(0, RuntimeException("boom"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(metroOption), vm.uiState.value.directionOptions)
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.directionsFailed)
    }

    @Test
    fun `retryDirections re-fetches for the same site`() = runTest(dispatcher) {
        val directions = FakeDirectionOptionsSource(emptyMap())
        val vm = viewModel(directions = directions)
        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.directionsEmpty)

        vm.retryDirections()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, directions.callCount)
    }

    @Test
    fun `reselecting the same stop after navigating back to STOP reloads its directions`() = runTest(dispatcher) {
        val directions = FakeDirectionOptionsSource(mapOf(9145L to listOf(busOption, metroOption)))
        val vm = viewModel(directions = directions)

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(1, directions.callCount)

        assertTrue(vm.back())
        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, directions.callCount)
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
    }

    // ---- Full flow / name / back / canSave ----

    @Test
    fun `full flow selects mode, direction, sets schedule, and saves`() = runTest(dispatcher) {
        val routines = FakeRoutineRepository()
        val vm = viewModel(routines = routines)

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectTransportMode(TransportMode.METRO)
        assertEquals(RoutineCreateStep.DIRECTION, vm.uiState.value.step)

        vm.selectDirection(metroOption)
        assertEquals(RoutineCreateStep.SCHEDULE, vm.uiState.value.step)
        assertEquals("14 → T-Centralen", vm.uiState.value.name)

        vm.toggleDay(DayOfWeek.MONDAY)
        vm.toggleDay(DayOfWeek.TUESDAY)
        vm.setStartTime(LocalTime.of(7, 0))
        vm.setEndTime(LocalTime.of(9, 0))

        assertTrue(vm.uiState.value.canSave)

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(saved)
        val routine = routines.saved.single()
        assertEquals(fruangen.siteId, routine.siteId)
        assertEquals(fruangen.name, routine.siteName)
        assertEquals(TransportMode.METRO, routine.transportMode)
        assertEquals(metroOption.lineId, routine.lineId)
        assertEquals(metroOption.directionCode, routine.directionCode)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), routine.activeDays)
    }

    @Test
    fun `manually edited name is not overwritten by a later direction selection`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectTransportMode(TransportMode.METRO)
        vm.selectDirection(metroOption)

        vm.setName("My custom name")
        vm.selectDirection(busOption)

        assertEquals("My custom name", vm.uiState.value.name)
    }

    @Test
    fun `back steps backward through the wizard and returns false at the first step`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectTransportMode(TransportMode.METRO)
        vm.selectDirection(metroOption)
        assertEquals(RoutineCreateStep.SCHEDULE, vm.uiState.value.step)

        assertTrue(vm.back())
        assertEquals(RoutineCreateStep.DIRECTION, vm.uiState.value.step)
        assertTrue(vm.back())
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertTrue(vm.back())
        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun `canSave is false without active days or with an invalid time range`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectTransportMode(TransportMode.METRO)
        vm.selectDirection(metroOption)

        assertFalse(vm.uiState.value.canSave)

        vm.toggleDay(DayOfWeek.MONDAY)
        vm.setStartTime(LocalTime.of(9, 0))
        vm.setEndTime(LocalTime.of(7, 0))

        assertFalse(vm.uiState.value.canSave)
        assertFalse(vm.uiState.value.isTimeRangeValid)
    }

    // ---- Saving ----

    @Test
    fun `a failed save does not call onSaved and resets isSaving`() = runTest(dispatcher) {
        val routines = FailingRoutineRepository()
        val vm = viewModel(routines = routines)
        vm.advanceToSaveReady()

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(saved)
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.isSaving)
        assertEquals(1, routines.saveCallCount)
    }

    @Test
    fun `retrying save after a failure succeeds once the underlying failure clears`() = runTest(dispatcher) {
        val routines = ToggleableRoutineRepository(shouldFail = true)
        val vm = viewModel(routines = routines)
        vm.advanceToSaveReady()

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(saved)

        routines.shouldFail = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(saved)
        assertFalse(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.isSaving)
        assertEquals(1, routines.saved.size)
    }

    @Test
    fun `a cancellation while saving is not converted into saveFailed and does not call onSaved`() = runTest(dispatcher) {
        val vm = viewModel(routines = CancellingRoutineRepository())
        vm.advanceToSaveReady()

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(saved)
        assertFalse(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `calling save while a save is already in flight only persists once`() = runTest(dispatcher) {
        val routines = SlowRoutineRepository()
        val vm = viewModel(routines = routines)
        vm.advanceToSaveReady()

        vm.save {}
        vm.save {}
        dispatcher.scheduler.runCurrent()

        assertEquals(1, routines.callCount)

        routines.release()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, routines.saved.size)
        assertFalse(vm.uiState.value.isSaving)
    }

    // ---- Edit mode ----

    private fun existingRoutine(
        id: String = "existing-1",
        lineId: Long? = 705,
        lineDesignation: String? = "705",
        transportMode: TransportMode = TransportMode.BUS,
        directionCode: Int? = 1,
        destinationLabel: String? = "Segeltorp",
        enabled: Boolean = true,
        pausedDate: LocalDate? = null,
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = fruangen.siteId,
        siteName = fruangen.name,
        transportMode = transportMode,
        lineId = lineId,
        lineDesignation = lineDesignation,
        directionCode = directionCode,
        destinationLabel = destinationLabel,
        activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        startTime = LocalTime.of(7, 30),
        endTime = LocalTime.of(8, 30),
        enabled = enabled,
        pausedDate = pausedDate,
    )

    @Test
    fun `edit mode loads the routine by navigation id and pre-fills every editable value`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isEditMode)
        assertFalse(state.isLoadingExistingRoutine)
        assertFalse(state.existingRoutineNotFound)
        assertEquals(fruangen.siteId, state.selectedSite?.siteId)
        assertEquals(TransportMode.BUS, state.selectedTransportMode)
        assertEquals(705L, state.selectedDirection?.lineId)
        assertEquals(1, state.selectedDirection?.directionCode)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), state.activeDays)
        assertEquals(LocalTime.of(7, 30), state.startTime)
        assertEquals(LocalTime.of(8, 30), state.endTime)
        assertEquals("Morning commute", state.name)
        assertEquals(RoutineCreateStep.SCHEDULE, state.step)
    }

    @Test
    fun `a missing routine id in edit mode is reported without crashing`() = runTest(dispatcher) {
        val routines = FakeRoutineRepository(emptyList())
        val vm = viewModel(routines = routines, routineId = "does-not-exist")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.existingRoutineNotFound)
        assertFalse(vm.uiState.value.isLoadingExistingRoutine)
    }

    @Test
    fun `saving an edit updates the existing routine id instead of inserting a duplicate`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setStartTime(LocalTime.of(6, 0))
        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(saved)
        // FakeRoutineRepository dedupes by id on save(); assert via getById + a single count.
        assertEquals(routine.id, routines.getById(routine.id)?.id)
        assertEquals(LocalTime.of(6, 0), routines.getById(routine.id)?.startTime)
    }

    @Test
    fun `editing preserves enabled and pausedDate from the original routine`() = runTest(dispatcher) {
        val today = LocalDate.of(2026, 7, 30)
        val routine = existingRoutine(enabled = false, pausedDate = today)
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        vm.setName("Renamed commute")
        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(saved)
        val updated = routines.getById(routine.id)
        assertEquals(false, updated?.enabled)
        assertEquals(today, updated?.pausedDate)
        assertEquals("Renamed commute", updated?.name)
    }

    @Test
    fun `validation still prevents saving an invalid edit`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        // Clearing every active day makes the routine invalid.
        vm.toggleDay(DayOfWeek.MONDAY)
        vm.toggleDay(DayOfWeek.WEDNESDAY)
        assertFalse(vm.uiState.value.canSave)

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(saved)
    }

    @Test
    fun `changing station in edit mode clears the pre-filled mode and direction`() = runTest(dispatcher) {
        val routine = existingRoutine()
        // slussen must have its own (live, non-preselected) options for selectSite to land
        // anywhere other than directionsEmpty -- distinct from fruangen's original bus line.
        val directions = FakeDirectionOptionsSource(
            mapOf(fruangen.siteId to listOf(busOption), slussen.siteId to listOf(metroOption)),
        )
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(directions = directions, routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TransportMode.BUS, vm.uiState.value.selectedTransportMode)

        vm.onSiteQueryChanged("Slu")
        assertEquals(null, vm.uiState.value.selectedSite)
        assertEquals(null, vm.uiState.value.selectedTransportMode)
        assertEquals(null, vm.uiState.value.selectedDirection)

        vm.selectSite(slussen)
        dispatcher.scheduler.advanceUntilIdle()
        // selectSite always fetches with preselect = null (see its doc comment), so it never
        // auto-picks a mode/direction even when only one option is available -- the user
        // must walk through TRANSPORT_MODE/DIRECTION themselves, same as plain creation. What
        // this test actually proves: slussen's own (distinct) live options were fetched
        // fresh, rather than the edit-mode pre-fill leaking through for the new station.
        assertEquals(null, vm.uiState.value.selectedTransportMode)
        assertEquals(null, vm.uiState.value.selectedDirection)
        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(listOf(TransportMode.METRO), vm.uiState.value.availableTransportModes)
    }

    @Test
    fun `a saved direction no longer live is still shown and preselected via synthesis`() = runTest(dispatcher) {
        // The live feed for this site currently only has the metro option running -- the
        // routine's originally-saved bus line/direction isn't in today's forecast window.
        val routine = existingRoutine()
        val directions = FakeDirectionOptionsSource(mapOf(fruangen.siteId to listOf(metroOption)))
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(directions = directions, routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(TransportMode.BUS, state.selectedTransportMode)
        assertEquals(705L, state.selectedDirection?.lineId)
        assertTrue(state.directionOptions.any { it.lineId == 705L })
        assertTrue(state.directionOptions.any { it.lineId == metroOption.lineId })
        assertFalse(state.directionsEmpty)
    }

    @Test
    fun `repeated save taps while editing only persist once`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        // Both calls fire before the dispatcher advances, so the first has already flipped
        // isSaving synchronously by the time the second is evaluated -- the ViewModel's own
        // in-flight guard must block the second one, regardless of repository speed.
        vm.save {}
        vm.save {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, routines.saved.size)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `a successful edit save triggers onSaved for navigation back`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FakeRoutineRepository(listOf(routine))
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()

        var navigatedBack = false
        vm.save { navigatedBack = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(navigatedBack)
    }

    /** Loads a real routine for editing (so save() actually reaches the edit-save path)
     * but always fails on save -- for proving edit-mode save failures are handled the same
     * friendly way as create-mode ones. */
    private class FailingEditRoutineRepository(private val existing: CommuteRoutine) : RoutineRepository {
        var saveCallCount = 0
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(listOf(existing))
        override suspend fun getById(id: String): CommuteRoutine? = existing.takeIf { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            saveCallCount++
            throw RuntimeException("boom")
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
        override suspend fun clearPause(id: String) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasAnyRoutine(): Boolean = true
    }

    @Test
    fun `an edit save failure produces a friendly failure state, not a crash`() = runTest(dispatcher) {
        val routine = existingRoutine()
        val routines = FailingEditRoutineRepository(routine)
        val vm = viewModel(routines = routines, routineId = routine.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.existingRoutineNotFound)

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(saved)
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.isSaving)
        assertEquals(1, routines.saveCallCount)
    }

    // ---- One-routine beta limit ----

    @Test
    fun `create mode is available when no routine exists yet`() = runTest(dispatcher) {
        val vm = viewModel(routines = FakeRoutineRepository(emptyList()))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.oneRoutineLimitReached)
    }

    @Test
    fun `create mode is blocked once a routine already exists`() = runTest(dispatcher) {
        val vm = viewModel(routines = FakeRoutineRepository(listOf(existingRoutine())))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.oneRoutineLimitReached)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun `a direct save attempt cannot create a second routine once the limit is reached`() = runTest(dispatcher) {
        val routines = FakeRoutineRepository(listOf(existingRoutine()))
        val vm = viewModel(routines = routines)
        dispatcher.scheduler.advanceUntilIdle()
        vm.advanceToSaveReady()

        var saved = false
        vm.save { saved = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(saved)
        // save() must never have been called at all -- the guard short-circuits before
        // reaching the repository, not merely after a failed write.
        assertEquals(0, routines.saved.size)
    }
}
