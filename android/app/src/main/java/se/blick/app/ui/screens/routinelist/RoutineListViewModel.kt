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

data class RoutineListUiState(
    val routines: List<CommuteRoutine> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class RoutineListViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
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
}
