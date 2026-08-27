package se.blick.app.ui.screens.onetimeevent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.repository.OneTimeEventRepository
import se.blick.app.domain.model.OneTimeEvent
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

data class OneTimeEventDetailsUiState(
    val isLoading: Boolean = true,
    val event: OneTimeEvent? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class OneTimeEventDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OneTimeEventRepository,
) : ViewModel() {
    private val eventId: String = checkNotNull(savedStateHandle["eventId"])
    private val _uiState = MutableStateFlow(OneTimeEventDetailsUiState())
    val uiState: StateFlow<OneTimeEventDetailsUiState> = _uiState.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _uiState.value = OneTimeEventDetailsUiState(isLoading = false, event = repository.getById(eventId))
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(eventId)
            _uiState.value = OneTimeEventDetailsUiState(isLoading = false, deleted = true)
        }
    }
}
