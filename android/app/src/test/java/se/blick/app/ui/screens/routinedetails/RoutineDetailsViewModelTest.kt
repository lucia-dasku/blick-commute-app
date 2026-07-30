package se.blick.app.ui.screens.routinedetails

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

    private class FakeRoutineRepository(private val routine: CommuteRoutine?) : RoutineRepository {
        var lastRequestedId: String? = null
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: String): CommuteRoutine? {
            lastRequestedId = id
            return routine?.takeIf { it.id == id }
        }
        override suspend fun save(routine: CommuteRoutine) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
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
}
