package se.blick.app.ui.screens.onetimeevent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.OneTimeEventPremiumRequiredException
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.domain.model.OneTimeEventValidationResult
import se.blick.app.domain.model.validateOneTimeEvent
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

enum class OneTimeEventEditorError { REQUIRED, PAST, SAME_LOCATION, PREMIUM_REQUIRED, SAVE_FAILED }

data class OneTimeEventEditorUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val hasPremium: Boolean = false,
    val label: OneTimeEventLabel = OneTimeEventLabel.EVENT,
    val name: String = "",
    val originQuery: String = "",
    val originResults: List<JourneyLocation> = emptyList(),
    val selectedOrigin: JourneyLocation? = null,
    val destinationQuery: String = "",
    val destinationResults: List<JourneyLocation> = emptyList(),
    val selectedDestination: JourneyLocation? = null,
    val date: LocalDate = LocalDate.now(STOCKHOLM_ZONE).plusDays(1),
    val time: LocalTime = LocalTime.of(18, 30),
    val timeType: OneTimeEventTimeType = OneTimeEventTimeType.ARRIVE_BY,
    val isSearchingOrigin: Boolean = false,
    val isSearchingDestination: Boolean = false,
    val searchFailed: Boolean = false,
    val isSaving: Boolean = false,
    val savedEventId: String? = null,
    val error: OneTimeEventEditorError? = null,
) {
    val canSave: Boolean
        get() = hasPremium && selectedOrigin != null && selectedDestination != null && !isSaving
}

@HiltViewModel
class OneTimeEventEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OneTimeEventRepository,
    private val journeyRepository: JourneyRepository,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val clock: Clock,
) : ViewModel() {
    private val eventId: String? = savedStateHandle["eventId"]
    private val _uiState = MutableStateFlow(OneTimeEventEditorUiState())
    val uiState: StateFlow<OneTimeEventEditorUiState> = _uiState.asStateFlow()
    private var original: OneTimeEvent? = null
    private var nameEditedManually = false
    private var originSearchJob: Job? = null
    private var destinationSearchJob: Job? = null

    init {
        viewModelScope.launch {
            val hasPremium = entitlementRepository.entitlement.value.hasPremiumAccess
            val event = eventId?.let { repository.getById(it) }
            original = event
            nameEditedManually = event != null
            _uiState.value = if (event == null) {
                OneTimeEventEditorUiState(
                    isLoading = false,
                    hasPremium = hasPremium,
                    date = clock.instant().atZone(STOCKHOLM_ZONE).toLocalDate().plusDays(1),
                )
            } else {
                OneTimeEventEditorUiState(
                    isLoading = false,
                    isEditing = true,
                    hasPremium = hasPremium,
                    label = event.label,
                    name = event.name,
                    originQuery = event.originName,
                    selectedOrigin = JourneyLocation(event.originId, event.originName),
                    destinationQuery = event.destinationName,
                    selectedDestination = JourneyLocation(event.destinationId, event.destinationName),
                    date = event.date,
                    time = event.time,
                    timeType = event.timeType,
                )
            }
        }
    }

    fun setLabel(label: OneTimeEventLabel) = update { it.copy(label = label, error = null) }

    fun setName(name: String) {
        nameEditedManually = true
        update { it.copy(name = name, error = null) }
    }

    fun setDate(date: LocalDate) = update { it.copy(date = date, error = null) }
    fun setTime(time: LocalTime) = update { it.copy(time = time, error = null) }
    fun setTimeType(type: OneTimeEventTimeType) = update { it.copy(timeType = type, error = null) }

    fun setOriginQuery(query: String) {
        update { it.copy(originQuery = query, selectedOrigin = null, originResults = emptyList(), searchFailed = false) }
        originSearchJob?.cancel()
        if (query.length < 2) return
        originSearchJob = search(query, origin = true)
    }

    fun setDestinationQuery(query: String) {
        update {
            it.copy(destinationQuery = query, selectedDestination = null, destinationResults = emptyList(), searchFailed = false)
        }
        destinationSearchJob?.cancel()
        if (query.length < 2) return
        destinationSearchJob = search(query, origin = false)
    }

    fun selectOrigin(location: JourneyLocation) = update {
        it.copy(originQuery = location.name, selectedOrigin = location, originResults = emptyList(), error = null)
    }

    fun selectDestination(location: JourneyLocation) {
        update {
            val generatedName = if (!nameEditedManually && it.name.isBlank()) location.name else it.name
            it.copy(
                destinationQuery = location.name,
                selectedDestination = location,
                destinationResults = emptyList(),
                name = generatedName,
                error = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        val origin = state.selectedOrigin
        val destination = state.selectedDestination
        if (!state.hasPremium) {
            update { it.copy(error = OneTimeEventEditorError.PREMIUM_REQUIRED) }
            return
        }
        when (validateOneTimeEvent(origin, destination, state.date, state.time, clock.instant())) {
            OneTimeEventValidationResult.REQUIRED -> {
                update { it.copy(error = OneTimeEventEditorError.REQUIRED) }
                return
            }
            OneTimeEventValidationResult.PAST -> {
                update { it.copy(error = OneTimeEventEditorError.PAST) }
                return
            }
            OneTimeEventValidationResult.SAME_LOCATION -> {
                update { it.copy(error = OneTimeEventEditorError.SAME_LOCATION) }
                return
            }
            OneTimeEventValidationResult.VALID -> Unit
        }
        val validOrigin = requireNotNull(origin)
        val validDestination = requireNotNull(destination)
        val now = clock.instant()
        val event = OneTimeEvent(
            id = original?.id ?: UUID.randomUUID().toString(),
            label = state.label,
            name = state.name.trim().ifBlank { validDestination.name },
            originId = validOrigin.id,
            originName = validOrigin.name,
            destinationId = validDestination.id,
            destinationName = validDestination.name,
            date = state.date,
            time = state.time,
            timeType = state.timeType,
            createdAt = original?.createdAt ?: now,
            updatedAt = now,
        )
        update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.save(event)
                original = event
                update { it.copy(isSaving = false, savedEventId = event.id) }
            } catch (_: OneTimeEventPremiumRequiredException) {
                update { it.copy(isSaving = false, error = OneTimeEventEditorError.PREMIUM_REQUIRED) }
            } catch (_: Exception) {
                update { it.copy(isSaving = false, error = OneTimeEventEditorError.SAVE_FAILED) }
            }
        }
    }

    private fun search(query: String, origin: Boolean) = viewModelScope.launch {
        delay(250)
        update {
            if (origin) it.copy(isSearchingOrigin = true) else it.copy(isSearchingDestination = true)
        }
        try {
            val results = journeyRepository.searchLocations(query)
            update {
                if (origin && it.originQuery == query) {
                    it.copy(originResults = results, isSearchingOrigin = false)
                } else if (!origin && it.destinationQuery == query) {
                    it.copy(destinationResults = results, isSearchingDestination = false)
                } else it
            }
        } catch (_: Exception) {
            update {
                if (origin) it.copy(originResults = emptyList(), isSearchingOrigin = false, searchFailed = true)
                else it.copy(destinationResults = emptyList(), isSearchingDestination = false, searchFailed = true)
            }
        }
    }

    private fun update(transform: (OneTimeEventEditorUiState) -> OneTimeEventEditorUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
