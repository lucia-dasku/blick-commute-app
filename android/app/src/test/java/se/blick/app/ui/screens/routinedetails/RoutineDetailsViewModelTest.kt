package se.blick.app.ui.screens.routinedetails

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.ui.navigation.Routes
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "BUSTERM")
    private val stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A")

    private fun sampleRoutine(
        id: String = "r1",
        pausedDate: LocalDate? = null,
        enabled: Boolean = true,
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.BUS,
        lineId = null,
        lineDesignation = "705",
        directionCode = null,
        destinationLabel = "Segeltorp",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        enabled = enabled,
        pausedDate = pausedDate,
    )

    private fun upcomingDeparture(id: String = UUID.randomUUID().toString()) = Departure(
        departureId = id,
        line = LineRef(id = 705, designation = "705", transportMode = TransportMode.BUS),
        direction = "Southbound",
        directionCode = 1,
        destination = "Segeltorp",
        via = null,
        stopArea = stopArea,
        stopPoint = stopPoint,
        scheduledTime = now.plusSeconds(300),
        expectedTime = null,
        state = "EXPECTED",
        isCancelled = false,
        journey = Journey(id = 1, state = "EXPECTED", predictionState = null),
        tripDeviations = emptyList(),
    )

    private fun resultOf(vararg departures: Departure) = DeparturesResult(
        fetchedAt = now,
        siteId = 9145,
        departures = departures.toList(),
        siteDeviations = emptyList(),
    )

    // ---- RoutineRepository fakes ----

    /** Mutable so action tests (enable/disable, pause/resume, delete, reload) can observe the
     * effect of a write through a later [getById] call, not just via the ViewModel's own
     * locally-merged copy. */
    private class FakeRoutineRepository(initial: CommuteRoutine?) : RoutineRepository {
        var lastRequestedId: String? = null
        private val state = MutableStateFlow(initial)
        val deletedIds = mutableListOf<String>()
        val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
        val pauseForDateCalls = mutableListOf<Pair<String, LocalDate>>()
        val clearPauseCalls = mutableListOf<String>()

        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(listOfNotNull(state.value))
        override suspend fun getById(id: String): CommuteRoutine? {
            lastRequestedId = id
            return state.value?.takeIf { it.id == id }
        }
        override suspend fun save(routine: CommuteRoutine) {
            state.value = routine
        }
        override suspend fun delete(id: String) {
            deletedIds += id
            if (state.value?.id == id) state.value = null
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            pauseForDateCalls += id to date
            state.update { current -> if (current?.id == id) current.copy(pausedDate = date) else current }
        }
        override suspend fun clearPause(id: String) {
            clearPauseCalls += id
            state.update { current -> if (current?.id == id) current.copy(pausedDate = null) else current }
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            setEnabledCalls += id to enabled
            state.update { current -> if (current?.id == id) current.copy(enabled = enabled) else current }
        }
        override suspend fun hasAnyRoutine(): Boolean = state.value != null
    }

    /** Always succeeds on [getById]/[save], but each of the four action writes can be
     * independently forced to fail — for proving a write failure leaves the previously
     * stored state untouched and surfaces a friendly, retryable failure flag instead of a
     * raw exception. */
    private class FailingActionRoutineRepository(
        initial: CommuteRoutine,
        private val failSetEnabled: Boolean = false,
        private val failPauseForDate: Boolean = false,
        private val failClearPause: Boolean = false,
        private val failDelete: Boolean = false,
    ) : RoutineRepository {
        private val state = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(listOf(state.value))
        override suspend fun getById(id: String): CommuteRoutine? = state.value.takeIf { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            state.value = routine
        }
        override suspend fun delete(id: String) {
            if (failDelete) throw RuntimeException("boom")
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            if (failPauseForDate) throw RuntimeException("boom")
            state.value = state.value.copy(pausedDate = date)
        }
        override suspend fun clearPause(id: String) {
            if (failClearPause) throw RuntimeException("boom")
            state.value = state.value.copy(pausedDate = null)
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            if (failSetEnabled) throw RuntimeException("boom")
            state.value = state.value.copy(enabled = enabled)
        }
        override suspend fun hasAnyRoutine(): Boolean = true
    }

    // ---- DepartureRepository fakes ----

    private class FakeDepartureRepository(private val result: DeparturesResult) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
            callCount++
            return result
        }
    }

    private class FailingDepartureRepository(private val error: Throwable) : DepartureRepository {
        override suspend fun getDepartures(siteId: Long): DeparturesResult = throw error
    }

    /** Succeeds with [result] while [shouldFail] is false; throws [failure] once flipped on
     * — for proving a later refresh failure falls back to Stale using the earlier success. */
    private class ToggleableDepartureRepository(
        private val result: DeparturesResult,
        var shouldFail: Boolean = false,
        private val failure: Throwable = IOException("network down"),
    ) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
            callCount++
            if (shouldFail) throw failure
            return result
        }
    }

    /** Each call suspends on its own [CompletableDeferred], indexed by call order — lets a
     * test resolve fetches out of order to reproduce a slow/superseded refresh. Same pattern
     * as RoutineCreateViewModelTest's ControllableDirectionOptionsSource. */
    private class ControllableDepartureRepository : DepartureRepository {
        private val pending = mutableListOf<CompletableDeferred<DeparturesResult>>()
        val callCount: Int get() = pending.size
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
            val deferred = CompletableDeferred<DeparturesResult>()
            pending += deferred
            return deferred.await()
        }
        fun complete(callIndex: Int, result: DeparturesResult) {
            pending[callIndex].complete(result)
        }
    }

    private fun viewModel(
        routine: CommuteRoutine? = sampleRoutine(),
        routineId: String = routine?.id ?: "missing",
        departures: DepartureRepository = FakeDepartureRepository(resultOf(upcomingDeparture())),
        routines: RoutineRepository = FakeRoutineRepository(routine),
    ) = RoutineDetailsViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to routineId)),
        routineRepository = routines,
        getLiveDepartures = GetLiveDeparturesUseCase(departures, clock),
        clock = clock,
    )

    // ---- Routine loading ----

    @Test
    fun `the correct routine is loaded using the navigation id`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r42")
        val repository = FakeRoutineRepository(routine)
        val vm = RoutineDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to "r42")),
            routineRepository = repository,
            getLiveDepartures = GetLiveDeparturesUseCase(FakeDepartureRepository(resultOf(upcomingDeparture())), clock),
            clock = clock,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("r42", repository.lastRequestedId)
        assertEquals(routine, vm.uiState.value.routine)
        assertFalse(vm.uiState.value.isRoutineLoading)
        assertFalse(vm.uiState.value.routineNotFound)
    }

    @Test
    fun `a missing routine is reported without crashing and no departures are fetched`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(routine = null, routineId = "does-not-exist", departures = departures)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.routineNotFound)
        assertFalse(vm.uiState.value.isRoutineLoading)
        assertNull(vm.uiState.value.routine)
        assertEquals(0, departures.callCount)
    }

    @Test
    fun `paused-today status reflects the routine's pausedDate against the injected clock`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val pausedToday = viewModel(routine = sampleRoutine(pausedDate = today))
        val pausedYesterday = viewModel(routine = sampleRoutine(id = "r2", pausedDate = today.minusDays(1)), routineId = "r2")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(pausedToday.uiState.value.isPausedToday)
        assertFalse(pausedYesterday.uiState.value.isPausedToday)
    }

    // ---- Departure loading wired to the engine ----

    @Test
    fun `the departure fetch begins automatically once the routine has loaded`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, departures.callCount)
    }

    @Test
    fun `Loading is shown until the departure fetch resolves, then Live`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)

        dispatcher.scheduler.advanceUntilIdle() // reach the fetch's suspension point
        assertEquals(LiveDeparturesState.Loading, vm.uiState.value.departures)

        departures.complete(0, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.departures is LiveDeparturesState.Live)
    }

    @Test
    fun `a Live state exposes the prepared departures returned by the engine`() = runTest(dispatcher) {
        val a = upcomingDeparture("dep-a")
        val b = upcomingDeparture("dep-b").copy(scheduledTime = now.plusSeconds(600))
        val vm = viewModel(departures = FakeDepartureRepository(resultOf(a, b)))
        dispatcher.scheduler.advanceUntilIdle()

        val live = vm.uiState.value.departures as LiveDeparturesState.Live
        assertEquals(listOf("dep-a", "dep-b"), live.snapshot.departures.map { it.departureId })
    }

    @Test
    fun `no matching departures produces NoUpcomingDepartures`() = runTest(dispatcher) {
        val vm = viewModel(departures = FakeDepartureRepository(resultOf()))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.departures is LiveDeparturesState.NoUpcomingDepartures)
    }

    @Test
    fun `a connectivity failure with no previous data produces Offline`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(IOException("unable to resolve host")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDeparturesState.Offline, vm.uiState.value.departures)
    }

    @Test
    fun `a non-connectivity failure with no previous data produces Unavailable`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(RuntimeException("boom")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDeparturesState.Unavailable, vm.uiState.value.departures)
    }

    @Test
    fun `a cancellation while fetching departures does not surface as an error state`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(CancellationException("test cancellation")))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value.departures
        assertEquals(LiveDeparturesState.Loading, state)
    }

    // ---- Snapshot retention + Stale fallback ----

    @Test
    fun `a refresh failure after a successful fetch shows Stale and preserves the previous departures`() = runTest(dispatcher) {
        val departures = ToggleableDepartureRepository(resultOf(upcomingDeparture("kept")))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        val liveSnapshot = (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot

        departures.shouldFail = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val stale = vm.uiState.value.departures
        assertTrue(stale is LiveDeparturesState.Stale)
        assertEquals(liveSnapshot, (stale as LiveDeparturesState.Stale).snapshot)
        assertEquals(listOf("kept"), stale.snapshot.departures.map { it.departureId })
    }

    // ---- Manual refresh ----

    @Test
    fun `refresh triggers a new departures fetch`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, departures.callCount)
    }

    @Test
    fun `existing departures remain visible while a manual refresh is in progress`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        departures.complete(0, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()
        val liveBeforeRefresh = vm.uiState.value.departures
        assertTrue(liveBeforeRefresh is LiveDeparturesState.Live)
        assertFalse(vm.uiState.value.isRefreshingDepartures)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle() // let the refresh reach its suspension point

        assertTrue(vm.uiState.value.isRefreshingDepartures)
        assertEquals(liveBeforeRefresh, vm.uiState.value.departures)

        departures.complete(1, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isRefreshingDepartures)
    }

    @Test
    fun `an older in-flight refresh cannot overwrite a newer refresh's result`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        departures.complete(0, resultOf(upcomingDeparture("initial")))
        dispatcher.scheduler.advanceUntilIdle()

        vm.refresh() // call index 1 (older refresh), left pending
        dispatcher.scheduler.advanceUntilIdle()
        vm.refresh() // call index 2 (newer refresh) -- must supersede index 1
        dispatcher.scheduler.advanceUntilIdle()

        departures.complete(2, resultOf(upcomingDeparture("newer")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("newer"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )

        // The older, superseded refresh finally resolves late -- must be ignored.
        departures.complete(1, resultOf(upcomingDeparture("stale-older")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("newer"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )
        assertFalse(vm.uiState.value.isRefreshingDepartures)
    }

    // ---- Enable / disable ----

    @Test
    fun `an enabled routine can be disabled`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertEquals(listOf(routine.id to false), repository.setEnabledCalls)
        assertFalse(vm.uiState.value.isTogglingEnabled)
    }

    @Test
    fun `a disabled routine can be enabled`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = false)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.routine?.enabled)
        assertEquals(listOf(routine.id to true), repository.setEnabledCalls)
    }

    @Test
    fun `toggling enabled never touches pausedDate`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertTrue(repository.pauseForDateCalls.isEmpty())
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a failed enable-disable write leaves the stored state untouched with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FailingActionRoutineRepository(routine, failSetEnabled = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.routine?.enabled)
        assertTrue(vm.uiState.value.enabledActionFailed)
        assertFalse(vm.uiState.value.isTogglingEnabled)
    }

    @Test
    fun `repeated enable-disable taps do not overlap`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.setEnabledCalls.size)
    }

    // ---- Pause / resume today ----

    @Test
    fun `pause today stores today's date from the injected clock`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        val today = LocalDate.now(clock)
        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id to today), repository.pauseForDateCalls)
    }

    @Test
    fun `resume today clears the pausedDate`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isPausedToday)

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id), repository.clearPauseCalls)
    }

    @Test
    fun `pausing today never touches the enabled flag`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = false)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertTrue(repository.setEnabledCalls.isEmpty())
    }

    @Test
    fun `a failed pause write leaves the stored state untouched with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FailingActionRoutineRepository(routine, failPauseForDate = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertTrue(vm.uiState.value.pauseActionFailed)
        assertFalse(vm.uiState.value.isTogglingPause)
    }

    @Test
    fun `repeated pause-resume taps do not overlap`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.pauseForDateCalls.size)
    }

    // ---- Expired pause auto-cleanup ----

    @Test
    fun `an expired pausedDate is cleared automatically on load`() = runTest(dispatcher) {
        val yesterday = LocalDate.now(clock).minusDays(1)
        val routine = sampleRoutine(pausedDate = yesterday)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id), repository.clearPauseCalls)
    }

    @Test
    fun `today's pause is not treated as expired`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a null pausedDate causes no cleanup write`() = runTest(dispatcher) {
        val routine = sampleRoutine(pausedDate = null)
        val repository = FakeRoutineRepository(routine)
        viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a future pausedDate is never treated as expired`() = runTest(dispatcher) {
        val tomorrow = LocalDate.now(clock).plusDays(1)
        val routine = sampleRoutine(pausedDate = tomorrow)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(tomorrow, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    // ---- Deletion ----

    @Test
    fun `confirming delete removes only that routine`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r9")
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routineId = "r9", routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("r9"), repository.deletedIds)
    }

    @Test
    fun `a successful delete invokes onDeleted so the caller can navigate to the list`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(deleted)
        assertFalse(vm.uiState.value.isDeleting)
    }

    @Test
    fun `a delete failure keeps the screen on the routine with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FailingActionRoutineRepository(routine, failDelete = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(deleted)
        assertTrue(vm.uiState.value.deleteFailed)
        assertFalse(vm.uiState.value.isDeleting)
        assertEquals(routine, vm.uiState.value.routine)
    }

    @Test
    fun `repeated confirm taps do not delete twice`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.deletedIds.size)
    }

    // ---- reload() after a successful edit (see BlickNavHost's savedStateHandle signal) ----

    @Test
    fun `reload fetches a new departure result once the routine's identity changes`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture("before")))
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        // Simulates what a successful edit does: routineRepository.save() upserts the same
        // id with a new site.
        repository.save(routine.copy(siteId = 9192, siteName = "Slussen"))

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Slussen", vm.uiState.value.routine?.siteName)
        assertEquals(2, departures.callCount)
    }

    @Test
    fun `reload does not re-fetch departures when the departure-relevant fields are unchanged`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        // A name-only edit -- same site/line/direction/mode, so no new fetch is warranted.
        repository.save(routine.copy(name = "Renamed"))

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Renamed", vm.uiState.value.routine?.name)
        assertEquals(1, departures.callCount)
    }

    @Test
    fun `a stale in-flight fetch for the old configuration cannot overwrite reload's new one`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = ControllableDepartureRepository()
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle() // reach the initial fetch's suspension point (call index 0)

        repository.save(routine.copy(siteId = 9192, siteName = "Slussen"))
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle() // reach reload's fetch's suspension point too (call index 1)

        // The OLD (pre-edit configuration) fetch finally resolves late -- must be ignored.
        departures.complete(0, resultOf(upcomingDeparture("stale-old-config")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LiveDeparturesState.Loading, vm.uiState.value.departures)

        // The NEW (post-edit configuration) fetch resolves -- this one must win.
        departures.complete(1, resultOf(upcomingDeparture("fresh")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("fresh"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )
    }
}
