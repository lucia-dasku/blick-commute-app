package se.blick.app.ui.screens.routinecreate

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

    private class FakeStopRepository(private val sitesByQuery: Map<String, List<Site>> = emptyMap()) : StopRepository {
        var lastQuery: String? = null
        override suspend fun searchStops(query: String): List<Site> {
            lastQuery = query
            return sitesByQuery[query] ?: emptyList()
        }
    }

    /** Regression fixture: a search backend failure must surface as an error, not as a
     * silent "no results" (see the 2026-07-28 production incident where this exact
     * masking made a real backend outage indistinguishable from a genuine empty search). */
    private class FailingStopRepository(private val message: String) : StopRepository {
        override suspend fun searchStops(query: String): List<Site> = throw RuntimeException(message)
    }

    private class FakeDirectionOptionsSource(
        private val optionsBySite: Map<Long, List<DirectionOption>> = emptyMap(),
    ) : DirectionOptionsSource {
        var callCount = 0
        override suspend fun getDirectionOptions(siteId: Long): List<DirectionOption> {
            callCount++
            return optionsBySite[siteId] ?: emptyList()
        }
    }

    private class FakeRoutineRepository : RoutineRepository {
        val saved = mutableListOf<CommuteRoutine>()
        private val state = MutableStateFlow<List<CommuteRoutine>>(emptyList())

        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            saved += routine
            state.value = state.value + routine
        }
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) = Unit
    }

    private fun viewModel(
        stops: StopRepository = FakeStopRepository(mapOf("Fru" to listOf(fruangen))),
        directions: DirectionOptionsSource = FakeDirectionOptionsSource(mapOf(9145L to listOf(busOption, metroOption))),
        routines: RoutineRepository = FakeRoutineRepository(),
    ) = RoutineCreateViewModel(stops, directions, routines)

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
    fun `a failed search surfaces the error message instead of an empty result`() = runTest(dispatcher) {
        val vm = viewModel(stops = FailingStopRepository("Unable to resolve host"))

        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.siteResults.isEmpty())
        assertEquals("Unable to resolve host", vm.uiState.value.searchErrorMessage)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `a later successful search clears a previous error`() = runTest(dispatcher) {
        val vm = viewModel(stops = FailingStopRepository("boom"))
        vm.onSiteQueryChanged("Fru")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("boom", vm.uiState.value.searchErrorMessage)

        vm.onSiteQueryChanged("")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.searchErrorMessage)
    }

    @Test
    fun `selecting a site loads direction options and advances to the transport mode step`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.TRANSPORT_MODE, vm.uiState.value.step)
        assertEquals(listOf(TransportMode.BUS, TransportMode.METRO), vm.uiState.value.availableTransportModes)
    }

    @Test
    fun `selecting a site with no live departures shows an error and stays on the stop step`() = runTest(dispatcher) {
        val vm = viewModel(directions = FakeDirectionOptionsSource(emptyMap()))

        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(RoutineCreateStep.STOP, vm.uiState.value.step)
        assertTrue(vm.uiState.value.directionsError)
    }

    @Test
    fun `retryDirections re-fetches for the same site`() = runTest(dispatcher) {
        val directions = FakeDirectionOptionsSource(emptyMap())
        val vm = viewModel(directions = directions)
        vm.selectSite(fruangen)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.directionsError)

        vm.retryDirections()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, directions.callCount)
    }

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
}
