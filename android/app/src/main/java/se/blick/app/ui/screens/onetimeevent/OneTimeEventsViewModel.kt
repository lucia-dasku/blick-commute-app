package se.blick.app.ui.screens.onetimeevent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.PlannedJourneyRole
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.domain.model.upcomingAt
import se.blick.app.domain.usecase.GetJourneyDisruptionRelevanceUseCase
import se.blick.app.scheduling.DISRUPTIONS_FETCH_TIMEOUT_MS
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class OneTimeEventsUiState(
    val isLoading: Boolean = true,
    val events: List<OneTimeEvent> = emptyList(),
    val entitlement: EntitlementState = EntitlementState.Loading,
)

@HiltViewModel
class OneTimeEventsViewModel @Inject constructor(
    repository: OneTimeEventRepository,
    entitlementRepository: PremiumEntitlementRepository,
    clock: Clock,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OneTimeEventsUiState())
    val uiState: StateFlow<OneTimeEventsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeAll(), entitlementRepository.entitlement) { events, entitlement ->
                OneTimeEventsUiState(
                    isLoading = false,
                    events = events.upcomingAt(clock.instant()),
                    entitlement = entitlement,
                )
            }.collect { _uiState.value = it }
        }
    }
}

sealed interface PlannedJourneyPreviewState {
    data object WaitingForEntitlement : PlannedJourneyPreviewState
    data object Loading : PlannedJourneyPreviewState
    data class Ready(val result: PlannedJourneyResult) : PlannedJourneyPreviewState {
        val recommended: JourneyPlan
            get() = checkNotNull(result.choices.firstOrNull { it.role == PlannedJourneyRole.RECOMMENDED }?.journey)
    }
    data object NoJourney : PlannedJourneyPreviewState
    data object Error : PlannedJourneyPreviewState
    data object Expired : PlannedJourneyPreviewState
    data object PremiumRequired : PlannedJourneyPreviewState
}

enum class EventPlanPresentation { PRELIMINARY, TODAY }

internal fun eventPlanPresentation(event: OneTimeEvent, now: Instant): EventPlanPresentation? {
    if (event.targetInstant() <= now) return null
    val today = now.atZone(STOCKHOLM_ZONE).toLocalDate()
    return when {
        event.date > today -> EventPlanPresentation.PRELIMINARY
        event.date == today -> EventPlanPresentation.TODAY
        else -> null
    }
}

sealed interface EventPlanDisruptionState {
    data object NotRequested : EventPlanDisruptionState
    data object Loading : EventPlanDisruptionState
    data class Ready(val disruptions: List<ResolvedJourneyDisruption>) : EventPlanDisruptionState
    data object Unavailable : EventPlanDisruptionState
}

data class OneTimeEventDetailsUiState(
    val isLoading: Boolean = true,
    val event: OneTimeEvent? = null,
    val deleted: Boolean = false,
    val entitlement: EntitlementState = EntitlementState.Loading,
    val preview: PlannedJourneyPreviewState = PlannedJourneyPreviewState.WaitingForEntitlement,
    val presentation: EventPlanPresentation? = null,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val disruptionState: EventPlanDisruptionState = EventPlanDisruptionState.NotRequested,
)

@HiltViewModel
class OneTimeEventDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OneTimeEventRepository,
    private val journeyRepository: JourneyRepository,
    private val getJourneyDisruptionRelevance: GetJourneyDisruptionRelevanceUseCase,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val clock: Clock,
) : ViewModel() {
    private val eventId: String = checkNotNull(savedStateHandle["eventId"])
    private val _uiState = MutableStateFlow(OneTimeEventDetailsUiState())
    val uiState: StateFlow<OneTimeEventDetailsUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var disruptionJob: Job? = null
    private var entitlement: EntitlementState = EntitlementState.Loading
    private var lastRequestedEvent: OneTimeEvent? = null

    init {
        viewModelScope.launch {
            entitlementRepository.entitlement.collect { latest ->
                entitlement = latest
                _uiState.value = _uiState.value.copy(entitlement = latest)
                updatePreview()
            }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val event = repository.getById(eventId)
            if (event != _uiState.value.event) lastRequestedEvent = null
            _uiState.value = _uiState.value.copy(isLoading = false, event = event)
            updatePreview()
        }
    }

    fun refreshPreview() = updatePreview(force = true)

    private fun updatePreview(force: Boolean = false) {
        val event = _uiState.value.event ?: return
        val presentation = eventPlanPresentation(event, clock.instant())
        when {
            entitlement is EntitlementState.Loading -> {
                previewJob?.cancel()
                disruptionJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    preview = PlannedJourneyPreviewState.WaitingForEntitlement,
                    presentation = presentation,
                    isRefreshing = false,
                    disruptionState = EventPlanDisruptionState.NotRequested,
                )
            }
            !entitlement.hasPremiumAccess -> {
                previewJob?.cancel()
                disruptionJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    preview = PlannedJourneyPreviewState.PremiumRequired,
                    presentation = presentation,
                    isRefreshing = false,
                    disruptionState = EventPlanDisruptionState.NotRequested,
                )
            }
            presentation == null -> {
                previewJob?.cancel()
                disruptionJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    preview = PlannedJourneyPreviewState.Expired,
                    presentation = null,
                    isRefreshing = false,
                    disruptionState = EventPlanDisruptionState.NotRequested,
                )
            }
            !force && lastRequestedEvent == event -> Unit
            else -> {
                previewJob?.cancel()
                disruptionJob?.cancel()
                lastRequestedEvent = event
                val existingReady = _uiState.value.preview as? PlannedJourneyPreviewState.Ready
                previewJob = viewModelScope.launch {
                    _uiState.value = if (force && existingReady != null) {
                        _uiState.value.copy(
                            presentation = presentation,
                            isRefreshing = true,
                            refreshFailed = false,
                        )
                    } else {
                        _uiState.value.copy(
                            preview = PlannedJourneyPreviewState.Loading,
                            presentation = presentation,
                            isRefreshing = false,
                            refreshFailed = false,
                            disruptionState = EventPlanDisruptionState.NotRequested,
                        )
                    }
                    val result = try {
                        Result.success(
                            journeyRepository.getPlannedJourneys(
                                originId = event.originId,
                                destinationId = event.destinationId,
                                allowedTransportModes = DEFAULT_JOURNEY_TRANSPORT_MODES,
                                searchMode = if (event.timeType == OneTimeEventTimeType.ARRIVE_BY) {
                                    JourneySearchMode.ARRIVE_BY
                                } else {
                                    JourneySearchMode.LEAVE_AT
                                },
                                requestedDateTime = event.date.atTime(event.time).atZone(STOCKHOLM_ZONE),
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                    if (_uiState.value.event != event || !entitlement.hasPremiumAccess) return@launch
                    result.fold(
                        onSuccess = { planned ->
                            val recommended = planned.choices
                                .firstOrNull { it.role == PlannedJourneyRole.RECOMMENDED }
                                ?.journey
                            if (recommended == null) {
                                _uiState.value = _uiState.value.copy(
                                    preview = PlannedJourneyPreviewState.NoJourney,
                                    isRefreshing = false,
                                    refreshFailed = false,
                                    disruptionState = EventPlanDisruptionState.NotRequested,
                                )
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    preview = PlannedJourneyPreviewState.Ready(planned),
                                    isRefreshing = false,
                                    refreshFailed = false,
                                    disruptionState = EventPlanDisruptionState.NotRequested,
                                )
                                if (presentation == EventPlanPresentation.TODAY) {
                                    loadDisruptions(event, planned, recommended)
                                }
                            }
                        },
                        onFailure = {
                            _uiState.value = if (force && existingReady != null) {
                                _uiState.value.copy(isRefreshing = false, refreshFailed = true)
                            } else {
                                _uiState.value.copy(
                                    preview = PlannedJourneyPreviewState.Error,
                                    isRefreshing = false,
                                    refreshFailed = false,
                                    disruptionState = EventPlanDisruptionState.NotRequested,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun loadDisruptions(event: OneTimeEvent, planned: PlannedJourneyResult, recommended: JourneyPlan) {
        disruptionJob?.cancel()
        _uiState.value = _uiState.value.copy(disruptionState = EventPlanDisruptionState.Loading)
        disruptionJob = viewModelScope.launch {
            val resolved = withTimeoutOrNull(DISRUPTIONS_FETCH_TIMEOUT_MS) {
                try {
                    Result.success(
                        getJourneyDisruptionRelevance(
                            recommended.legs,
                            null,
                            recommended.disruptionNotices,
                            recommended.disruptionContext,
                            recommended.departureTime,
                            recommended.arrivalTime,
                            event.originId,
                            event.destinationId,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
            val currentReady = _uiState.value.preview as? PlannedJourneyPreviewState.Ready
            if (
                _uiState.value.event != event ||
                _uiState.value.presentation != EventPlanPresentation.TODAY ||
                currentReady?.result != planned
            ) return@launch
            _uiState.value = _uiState.value.copy(
                disruptionState = resolved?.fold(
                    onSuccess = { EventPlanDisruptionState.Ready(it) },
                    onFailure = { EventPlanDisruptionState.Unavailable },
                ) ?: EventPlanDisruptionState.Unavailable,
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            previewJob?.cancel()
            disruptionJob?.cancel()
            repository.delete(eventId)
            _uiState.value = _uiState.value.copy(isLoading = false, event = null, deleted = true)
        }
    }
}
