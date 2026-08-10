package se.blick.app.ui.screens.routinelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.billing.FreePremiumEntitlementRepository

data class RoutineListUiState(
    val routines: List<CommuteRoutine> = emptyList(),
    val isLoading: Boolean = true,
    val entitlement: EntitlementState = EntitlementState.Free,
    val selectedFreeRoutineId: String? = null,
)

@HiltViewModel
class RoutineListViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineListUiState())
    val uiState: StateFlow<RoutineListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                routineRepository.observeAll(),
                entitlementRepository.entitlement,
                freeRoutineSelectionStore?.selectedRoutineId ?: MutableStateFlow(null),
            ) { routines, entitlement, selectedId -> Triple(routines, entitlement, selectedId) }.collect {
                (routines, entitlement, selectedId) ->
                _uiState.value = RoutineListUiState(routines, false, entitlement, selectedId)
            }
        }
    }

    fun selectFreeRoutine(id: String) = freeRoutineSelectionStore?.select(id)
}
