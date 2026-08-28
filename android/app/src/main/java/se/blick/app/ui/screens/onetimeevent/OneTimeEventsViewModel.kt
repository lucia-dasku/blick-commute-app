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
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.domain.model.upcomingAt
import java.time.Clock
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
    data class Ready(
        val primary: JourneyPlan,
        val result: PlannedJourneyResult,
    ) : PlannedJourneyPreviewState
    data object NoJourney : PlannedJourneyPreviewState
    data object Error : PlannedJourneyPreviewState
    data object Expired : PlannedJourneyPreviewState
    data object PremiumRequired : PlannedJourneyPreviewState
}

data class OneTimeEventDetailsUiState(
    val isLoading: Boolean = true,
    val event: OneTimeEvent? = null,
    val deleted: Boolean = false,
    val entitlement: EntitlementState = EntitlementState.Loading,
    val preview: PlannedJourneyPreviewState = PlannedJourneyPreviewState.WaitingForEntitlement,
)

@HiltViewModel
class OneTimeEventDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OneTimeEventRepository,
    private val journeyRepository: JourneyRepository,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val clock: Clock,
) : ViewModel() {
    private val eventId: String = checkNotNull(savedStateHandle["eventId"])
    private val _uiState = MutableStateFlow(OneTimeEventDetailsUiState())
    val uiState: StateFlow<OneTimeEventDetailsUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
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
        when {
            entitlement is EntitlementState.Loading -> {
                previewJob?.cancel()
                _uiState.value = _uiState.value.copy(preview = PlannedJourneyPreviewState.WaitingForEntitlement)
            }
            !entitlement.hasPremiumAccess -> {
                previewJob?.cancel()
                _uiState.value = _uiState.value.copy(preview = PlannedJourneyPreviewState.PremiumRequired)
            }
            event.targetInstant() <= clock.instant() -> {
                previewJob?.cancel()
                _uiState.value = _uiState.value.copy(preview = PlannedJourneyPreviewState.Expired)
            }
            !force && lastRequestedEvent == event -> Unit
            else -> {
                previewJob?.cancel()
                lastRequestedEvent = event
                previewJob = viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(preview = PlannedJourneyPreviewState.Loading)
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
                    _uiState.value = _uiState.value.copy(
                        preview = result.fold(
                            onSuccess = { planned ->
                                planned.journeys.firstOrNull { it.role == JourneyRole.PRIMARY }?.let { primary ->
                                    PlannedJourneyPreviewState.Ready(primary, planned)
                                } ?: PlannedJourneyPreviewState.NoJourney
                            },
                            onFailure = { PlannedJourneyPreviewState.Error },
                        ),
                    )
                }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            previewJob?.cancel()
            repository.delete(eventId)
            _uiState.value = _uiState.value.copy(isLoading = false, event = null, deleted = true)
        }
    }
}
