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
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.LocalDate
import javax.inject.Inject

data class RoutineListUiState(
    val routines: List<CommuteRoutine> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class RoutineListViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineListUiState())
    val uiState: StateFlow<RoutineListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            routineRepository.observeAll().collect { routines ->
                _uiState.value = RoutineListUiState(routines = routines, isLoading = false)
            }
        }
    }

    fun deleteRoutine(id: String) {
        viewModelScope.launch {
            routineRepository.delete(id)
            routineScheduler.cancelActivation(id)
            // Best-effort -- viewModelScope has no default exception handler, so an uncaught
            // widget/Glance/DataStore failure here would crash the app even though the delete
            // above already succeeded (see runWidgetUpdateSafely's own doc).
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
    }

    fun pauseForToday(id: String) {
        viewModelScope.launch {
            routineRepository.pauseForDate(id, LocalDate.now())
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
    }
}
