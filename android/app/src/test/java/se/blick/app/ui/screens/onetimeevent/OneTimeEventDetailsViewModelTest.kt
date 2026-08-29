package se.blick.app.ui.screens.onetimeevent

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.data.repository.UnexpectedJourneyContextException
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.JourneyDisruptionContext
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.usecase.GetJourneyDisruptionRelevanceUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class OneTimeEventDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-08-10T10:00:00Z")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `future ARRIVE_BY requests planned journey and uses backend PRIMARY without re-ranking`() = runTest(dispatcher.scheduler) {
        val event = event(timeType = OneTimeEventTimeType.ARRIVE_BY)
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request -> plannedResult(request.mode, event, listOf(journey("next", JourneyRole.NEXT), journey("primary", JourneyRole.PRIMARY))) }
        }
        val viewModel = viewModel(event, journeyRepository)

        advanceUntilIdle()

        assertEquals(JourneySearchMode.ARRIVE_BY, journeyRepository.requests.single().mode)
        val ready = viewModel.uiState.value.preview as PlannedJourneyPreviewState.Ready
        assertEquals("primary", ready.primary.journeyId)
        assertEquals(EventPlanPresentation.PRELIMINARY, viewModel.uiState.value.presentation)
        assertTrue(journeyRepository.disruptionRequests == 0)
    }

    @Test
    fun `event on Stockholm today presents todays plan and resolves disruptions only after journey success`() = runTest(dispatcher.scheduler) {
        val event = event(date = LocalDate.of(2026, 8, 10), time = LocalTime.of(18, 30))
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request -> plannedResult(request.mode, event) }
        }
        val viewModel = viewModel(event, journeyRepository)

        advanceUntilIdle()

        assertEquals(EventPlanPresentation.TODAY, viewModel.uiState.value.presentation)
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Ready)
        assertEquals(1, journeyRepository.disruptionRequests)
        assertTrue(viewModel.uiState.value.disruptionState is EventPlanDisruptionState.Ready)
    }

    @Test
    fun `event day disruption failure never removes the journey`() = runTest(dispatcher.scheduler) {
        val event = event(date = LocalDate.of(2026, 8, 10), time = LocalTime.of(18, 30))
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request -> plannedResult(request.mode, event) }
            disruptionHandler = { throw java.io.IOException("disruptions unavailable") }
        }
        val viewModel = viewModel(event, journeyRepository)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Ready)
        assertTrue(viewModel.uiState.value.disruptionState is EventPlanDisruptionState.Unavailable)
    }

    @Test
    fun `event day refresh replaces the journey before resolving its disruptions`() = runTest(dispatcher.scheduler) {
        val event = event(date = LocalDate.of(2026, 8, 10), time = LocalTime.of(18, 30))
        var planNumber = 0
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request ->
                planNumber++
                plannedResult(
                    request.mode,
                    event,
                    journeys = listOf(journey("primary-$planNumber")),
                )
            }
        }
        val viewModel = viewModel(event, journeyRepository)
        advanceUntilIdle()

        viewModel.refreshPreview()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.preview as PlannedJourneyPreviewState.Ready
        assertEquals("primary-2", ready.primary.journeyId)
        assertEquals(2, journeyRepository.requests.size)
        assertEquals(2, journeyRepository.disruptionRequests)
        assertTrue(viewModel.uiState.value.disruptionState is EventPlanDisruptionState.Ready)
    }

    @Test
    fun `future LEAVE_AT requests LEAVE_AT using the event target`() = runTest(dispatcher.scheduler) {
        val event = event(timeType = OneTimeEventTimeType.LEAVE_AT)
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request -> plannedResult(request.mode, event) }
        }

        viewModel(event, journeyRepository)
        advanceUntilIdle()

        val request = journeyRepository.requests.single()
        assertEquals(JourneySearchMode.LEAVE_AT, request.mode)
        assertEquals(event.targetInstant(), request.requestedDateTime.toInstant())
    }

    @Test
    fun `event remains visible while planned journey is loading`() = runTest(dispatcher.scheduler) {
        val event = event()
        val gate = CompletableDeferred<PlannedJourneyResult>()
        val journeyRepository = FakeJourneyRepository().apply { handler = { gate.await() } }
        val viewModel = viewModel(event, journeyRepository)

        runCurrent()

        assertEquals(event, viewModel.uiState.value.event)
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Loading)
        gate.complete(plannedResult(JourneySearchMode.ARRIVE_BY, event))
        advanceUntilIdle()
    }

    @Test
    fun `network error preserves event and retry replaces error with result`() = runTest(dispatcher.scheduler) {
        val event = event()
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { throw java.io.IOException("offline") }
        }
        val viewModel = viewModel(event, journeyRepository)
        advanceUntilIdle()

        assertEquals(event, viewModel.uiState.value.event)
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Error)

        journeyRepository.handler = { request -> plannedResult(request.mode, event) }
        viewModel.refreshPreview()
        advanceUntilIdle()

        assertEquals(2, journeyRepository.requests.size)
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Ready)
    }

    @Test
    fun `expired event does not request a planned journey`() = runTest(dispatcher.scheduler) {
        val expired = event(date = LocalDate.of(2026, 8, 10), time = LocalTime.of(12, 0))
        val journeyRepository = FakeJourneyRepository()
        val viewModel = viewModel(expired, journeyRepository)

        advanceUntilIdle()

        assertTrue(journeyRepository.requests.isEmpty())
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Expired)
    }

    @Test
    fun `editing planning fields causes reload to request with the updated event`() = runTest(dispatcher.scheduler) {
        val original = event(time = LocalTime.of(18, 30))
        val eventRepository = FakeEventRepository(original)
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { request -> plannedResult(request.mode, eventRepository.current!!) }
        }
        val viewModel = viewModel(eventRepository, journeyRepository, EntitlementState.Premium)
        advanceUntilIdle()

        val edited = original.copy(destinationId = "new-destination", destinationName = "Arena", time = LocalTime.of(19, 15))
        eventRepository.current = edited
        viewModel.reload()
        advanceUntilIdle()

        assertEquals(2, journeyRepository.requests.size)
        assertEquals("new-destination", journeyRepository.requests.last().destinationId)
        assertEquals(edited.targetInstant(), journeyRepository.requests.last().requestedDateTime.toInstant())
    }

    @Test
    fun `context mismatch becomes an error and is never presented as planned`() = runTest(dispatcher.scheduler) {
        val event = event()
        val journeyRepository = FakeJourneyRepository().apply {
            handler = { throw UnexpectedJourneyContextException("LIVE response") }
        }
        val viewModel = viewModel(event, journeyRepository)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.Error)
    }

    @Test
    fun `lost Premium preserves event and does not request or expose preview`() = runTest(dispatcher.scheduler) {
        val event = event()
        val journeyRepository = FakeJourneyRepository()
        val viewModel = viewModel(event, journeyRepository, EntitlementState.Free)

        advanceUntilIdle()

        assertEquals(event, viewModel.uiState.value.event)
        assertTrue(journeyRepository.requests.isEmpty())
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.PremiumRequired)
    }

    @Test
    fun `losing Premium cancels an in-flight preview and preserves the event`() = runTest(dispatcher.scheduler) {
        val event = event()
        val gate = CompletableDeferred<PlannedJourneyResult>()
        val journeyRepository = FakeJourneyRepository().apply { handler = { gate.await() } }
        val entitlementRepository = FakeEntitlementRepository(EntitlementState.Premium)
        val viewModel = OneTimeEventDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("eventId" to event.id)),
            repository = FakeEventRepository(event),
            journeyRepository = journeyRepository,
            getJourneyDisruptionRelevance = GetJourneyDisruptionRelevanceUseCase(journeyRepository),
            entitlementRepository = entitlementRepository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        runCurrent()

        entitlementRepository.entitlement.value = EntitlementState.Free
        runCurrent()

        assertEquals(event, viewModel.uiState.value.event)
        assertTrue(viewModel.uiState.value.preview is PlannedJourneyPreviewState.PremiumRequired)
    }

    private fun viewModel(
        event: OneTimeEvent,
        journeyRepository: FakeJourneyRepository,
        entitlement: EntitlementState = EntitlementState.Premium,
    ) = viewModel(FakeEventRepository(event), journeyRepository, entitlement)

    private fun viewModel(
        eventRepository: FakeEventRepository,
        journeyRepository: FakeJourneyRepository,
        entitlement: EntitlementState,
    ) = OneTimeEventDetailsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("eventId" to requireNotNull(eventRepository.current).id)),
        repository = eventRepository,
        journeyRepository = journeyRepository,
        getJourneyDisruptionRelevance = GetJourneyDisruptionRelevanceUseCase(journeyRepository),
        entitlementRepository = FakeEntitlementRepository(entitlement),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun event(
        date: LocalDate = LocalDate.of(2026, 9, 17),
        time: LocalTime = LocalTime.of(18, 30),
        timeType: OneTimeEventTimeType = OneTimeEventTimeType.ARRIVE_BY,
    ) = OneTimeEvent(
        id = "event-1",
        label = OneTimeEventLabel.EVENT,
        name = "Concert at Globen",
        originId = "origin",
        originName = "Home",
        destinationId = "destination",
        destinationName = "Globen",
        date = date,
        time = time,
        timeType = timeType,
    )

    private fun journey(id: String = "primary", role: JourneyRole = JourneyRole.PRIMARY): JourneyPlan {
        val leg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "19",
            direction = "Hagsätra",
            originName = "Home",
            destinationName = "Globen",
            departureTime = Instant.parse("2026-09-17T15:36:00Z"),
            arrivalTime = Instant.parse("2026-09-17T16:18:00Z"),
            isRealtime = false,
            disruptions = emptyList(),
        )
        return JourneyPlan(
            journeyId = id,
            originName = "Home",
            destinationName = "Globen",
            departureTime = requireNotNull(leg.departureTime),
            arrivalTime = requireNotNull(leg.arrivalTime),
            transferCount = 0,
            firstLeg = leg,
            legs = listOf(leg),
            disruptions = emptyList(),
            role = role,
        )
    }

    private fun plannedResult(
        mode: JourneySearchMode,
        event: OneTimeEvent,
        journeys: List<JourneyPlan> = listOf(journey()),
    ) = PlannedJourneyResult(
        fetchedAt = now,
        searchMode = mode,
        requestedDateTime = event.targetInstant(),
        journeys = journeys,
    )

    private class FakeEventRepository(initial: OneTimeEvent?) : OneTimeEventRepository {
        var current = initial
        override fun observeAll(): Flow<List<OneTimeEvent>> = MutableStateFlow(listOfNotNull(current))
        override suspend fun getById(id: String): OneTimeEvent? = current?.takeIf { it.id == id }
        override suspend fun save(event: OneTimeEvent) { current = event }
        override suspend fun delete(id: String) { if (current?.id == id) current = null }
    }

    private data class PlannedRequest(
        val originId: String,
        val destinationId: String,
        val mode: JourneySearchMode,
        val requestedDateTime: ZonedDateTime,
    )

    private class FakeJourneyRepository : JourneyRepository {
        val requests = mutableListOf<PlannedRequest>()
        var disruptionRequests = 0
        var handler: suspend (PlannedRequest) -> PlannedJourneyResult = { throw AssertionError("Unexpected request") }
        var disruptionHandler: suspend () -> List<ResolvedJourneyDisruption> = { emptyList() }

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
            val request = PlannedRequest(originId, destinationId, searchMode, requestedDateTime)
            requests += request
            return handler(request)
        }

        override suspend fun getRelevantDeviationNotices(
            legs: List<JourneyLeg>,
            originSiteId: Long?,
            journeyPlannerNotices: List<JourneyDisruptionNotice>,
            disruptionContext: JourneyDisruptionContext?,
            departureTime: Instant?,
            arrivalTime: Instant?,
            journeyOriginId: String?,
            journeyDestinationId: String?,
        ): List<ResolvedJourneyDisruption> {
            disruptionRequests++
            return disruptionHandler()
        }
    }

    private class FakeEntitlementRepository(initial: EntitlementState) : PremiumEntitlementRepository {
        override val entitlement = MutableStateFlow(initial)
        override val localizedPrice = MutableStateFlow<String?>(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }
}
