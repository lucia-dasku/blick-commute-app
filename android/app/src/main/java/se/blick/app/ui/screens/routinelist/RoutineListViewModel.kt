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
import se.blick.app.domain.model.RoutineLabel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.billing.FreePremiumEntitlementRepository
import se.blick.app.billing.EmptyPremiumRoutineOrderStore
import se.blick.app.billing.PremiumRoutineOrderStore

data class RoutineListUiState(
    val routines: List<CommuteRoutine> = emptyList(),
    val isLoading: Boolean = true,
    val entitlement: EntitlementState = EntitlementState.Free,
    val selectedFreeRoutineId: String? = null,
)

private val ROUTINE_LABEL_PRIORITY = mapOf(
    RoutineLabel.HOME to 0,
    RoutineLabel.STUDY to 1,
    RoutineLabel.WORK to 2,
    RoutineLabel.GYM to 3,
    RoutineLabel.HOBBY to 4,
    RoutineLabel.OTHER to 5,
)

/** Stable label grouping for the home list. Room already supplies the sensible name order, so
 * sorting only by this priority preserves that existing order within each label. */
internal fun List<CommuteRoutine>.sortedForRoutineList(): List<CommuteRoutine> =
    sortedBy { routine -> ROUTINE_LABEL_PRIORITY[routine.label] ?: Int.MAX_VALUE }

internal fun List<CommuteRoutine>.applySavedRoutineOrder(orderedRoutineIds: List<String>): List<CommuteRoutine> {
    if (orderedRoutineIds.isEmpty()) return this
    val routinesById = associateBy(CommuteRoutine::id)
    val orderedIds = orderedRoutineIds.toSet()
    return orderedRoutineIds.mapNotNull(routinesById::get) + filterNot { it.id in orderedIds }
}

@HiltViewModel
class RoutineListViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore? = null,
    private val premiumRoutineOrderStore: PremiumRoutineOrderStore = EmptyPremiumRoutineOrderStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineListUiState())
    val uiState: StateFlow<RoutineListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                routineRepository.observeAll(),
                entitlementRepository.entitlement,
                freeRoutineSelectionStore?.selectedRoutineId ?: MutableStateFlow(null),
                premiumRoutineOrderStore.orderedRoutineIds,
            ) { routines, entitlement, selectedId, orderedRoutineIds ->
                val defaultOrder = routines.sortedForRoutineList()
                val visibleOrder = if (entitlement.hasPremiumAccess) {
                    defaultOrder.applySavedRoutineOrder(orderedRoutineIds)
                } else {
                    defaultOrder
                }
                RoutineListUiState(
                    routines = visibleOrder,
                    isLoading = false,
                    entitlement = entitlement,
                    selectedFreeRoutineId = selectedId,
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    fun selectFreeRoutine(id: String) = freeRoutineSelectionStore?.select(id)

    fun moveRoutine(draggedRoutineId: String, targetRoutineId: String) {
        if (!_uiState.value.entitlement.hasPremiumAccess || draggedRoutineId == targetRoutineId) return
        val reordered = _uiState.value.routines.toMutableList()
        val fromIndex = reordered.indexOfFirst { it.id == draggedRoutineId }
        val targetIndex = reordered.indexOfFirst { it.id == targetRoutineId }
        if (fromIndex < 0 || targetIndex < 0) return
        val routine = reordered.removeAt(fromIndex)
        reordered.add(targetIndex.coerceAtMost(reordered.size), routine)
        premiumRoutineOrderStore.saveOrder(reordered.map(CommuteRoutine::id))
    }
}
