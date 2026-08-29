package se.blick.app.ui.screens.onetimeevent

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.TransportMode
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class OneTimeEventEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `new event save persists once and exposes its id without fetching a plan in the editor`() = runTest(dispatcher.scheduler) {
        val events = FakeEventRepository()
        val journeys = FakeJourneyRepository()
        val viewModel = viewModel(SavedStateHandle(), events, journeys)
        advanceUntilIdle()

        viewModel.selectOrigin(JourneyLocation("origin", "Home"))
        viewModel.selectDestination(JourneyLocation("destination", "Globen"))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, events.saved.size)
        assertEquals(events.saved.single().id, viewModel.uiState.value.savedEventId)
        assertEquals(0, journeys.plannedRequests)
    }

    @Test
    fun `edit preserves the event id so navigation returns to the existing details destination`() = runTest(dispatcher.scheduler) {
        val original = OneTimeEvent(
            id = "event-1",
            label = se.blick.app.domain.model.OneTimeEventLabel.EVENT,
            name = "Concert",
            originId = "origin",
            originName = "Home",
            destinationId = "destination",
            destinationName = "Globen",
            date = java.time.LocalDate.of(2026, 8, 11),
            time = java.time.LocalTime.of(18, 30),
        )
        val events = FakeEventRepository(original)
        val viewModel = viewModel(SavedStateHandle(mapOf("eventId" to original.id)), events, FakeJourneyRepository())
        advanceUntilIdle()

        viewModel.setName("Updated concert")
        viewModel.save()
        advanceUntilIdle()

        assertEquals("event-1", events.saved.single().id)
        assertEquals("event-1", viewModel.uiState.value.savedEventId)
    }

    private fun viewModel(
        handle: SavedStateHandle,
        events: FakeEventRepository,
        journeys: FakeJourneyRepository,
    ) = OneTimeEventEditorViewModel(
        savedStateHandle = handle,
        repository = events,
        journeyRepository = journeys,
        entitlementRepository = FakeEntitlementRepository(),
        clock = clock,
    )

    private class FakeEventRepository(initial: OneTimeEvent? = null) : OneTimeEventRepository {
        var current = initial
        val saved = mutableListOf<OneTimeEvent>()
        override fun observeAll(): Flow<List<OneTimeEvent>> = MutableStateFlow(listOfNotNull(current))
        override suspend fun getById(id: String): OneTimeEvent? = current?.takeIf { it.id == id }
        override suspend fun save(event: OneTimeEvent) { current = event; saved += event }
        override suspend fun delete(id: String) = Unit
    }

    private class FakeJourneyRepository : JourneyRepository {
        var plannedRequests = 0
        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
            searchUntil: Instant?,
            changesPreference: ExactDestinationChangesPreference,
        ): List<JourneyPlan> = emptyList()

        override suspend fun getPlannedJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
            searchMode: JourneySearchMode,
            requestedDateTime: ZonedDateTime,
        ): PlannedJourneyResult {
            plannedRequests++
            throw AssertionError("The editor must not fetch a journey plan")
        }
    }

    private class FakeEntitlementRepository : PremiumEntitlementRepository {
        override val entitlement = MutableStateFlow<EntitlementState>(EntitlementState.Premium)
        override val localizedPrice = MutableStateFlow<String?>(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }
}
