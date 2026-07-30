package se.blick.app.ui.screens.routinedetails

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.ui.navigation.Routes
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Foreground, manually-refreshable UI state for the routine details/live-preview screen.
 * A flat data class (matching [se.blick.app.ui.screens.routinecreate.RoutineCreateUiState]'s
 * convention) rather than a deep sealed hierarchy, since several of these fields are
 * genuinely independent (the routine can be loaded while departures are still refreshing).
 */
data class RoutineDetailsUiState(
    val isRoutineLoading: Boolean = true,
    val routineNotFound: Boolean = false,
    val routine: CommuteRoutine? = null,
    /** Derived from [CommuteRoutine.pausedDate] vs. "today" — see [RoutineDetailsViewModel]'s
     * injected [Clock] for why this isn't just `LocalDate.now()`. */
    val isPausedToday: Boolean = false,
    /**
     * The live-departures engine's own state. On a manual refresh this is deliberately
     * NOT reset back to [LiveDeparturesState.Loading] — see [isRefreshingDepartures] — so
     * the previously loaded departures (or empty/error message) stay on screen instead of
     * the whole section blanking out while refreshing.
     */
    val departures: LiveDeparturesState = LiveDeparturesState.Loading,
    val isRefreshingDepartures: Boolean = false,
)

/**
 * Loads one saved [CommuteRoutine] by the id supplied via navigation, then fetches its next
 * two relevant departures through the existing [GetLiveDeparturesUseCase] — no departure
 * filtering, sorting, countdown, or failure-classification logic is reimplemented here.
 *
 * This is a foreground, manually-refreshable preview only: there is no periodic background
 * refresh loop, no notification, and no persistence beyond this ViewModel's own lifetime —
 * see [GetLiveDeparturesUseCase]'s doc comment on why the last successful
 * [LiveDeparturesSnapshot] must be held in memory by the caller (here) rather than by the
 * engine itself.
 */
@HiltViewModel
class RoutineDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val getLiveDepartures: GetLiveDeparturesUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val routineId: String =
        checkNotNull(savedStateHandle.get<String>(Routes.RoutineDetails.ARG_ROUTINE_ID)) {
            "RoutineDetailsViewModel requires a '${Routes.RoutineDetails.ARG_ROUTINE_ID}' navigation argument"
        }

    private val _uiState = MutableStateFlow(RoutineDetailsUiState())
    val uiState: StateFlow<RoutineDetailsUiState> = _uiState.asStateFlow()

    /** The last successful fetch, kept in memory only (see class doc) so a later failed
     * refresh can fall back to [LiveDeparturesState.Stale] instead of losing the data. */
    private var lastSnapshot: LiveDeparturesSnapshot? = null

    /** Guards against an overlapping/superseded departures fetch overwriting a newer one —
     * same stored-Job + request-generation-token pattern as
     * [se.blick.app.ui.screens.routinecreate.RoutineCreateViewModel]'s direction-race fix. */
    private var departuresJob: Job? = null
    private var departuresRequestId = 0

    init {
        viewModelScope.launch {
            val routine = routineRepository.getById(routineId)
            if (routine == null) {
                _uiState.update { it.copy(isRoutineLoading = false, routineNotFound = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isRoutineLoading = false,
                        routine = routine,
                        isPausedToday = routine.pausedDate == LocalDate.now(clock),
                    )
                }
                loadDepartures(routine, isManualRefresh = false)
            }
        }
    }

    /** Manual refresh action for the "Next departures" section. A no-op if the routine
     * itself never loaded (nothing to refresh departures for). */
    fun refresh() {
        val routine = _uiState.value.routine ?: return
        loadDepartures(routine, isManualRefresh = true)
    }

    private fun loadDepartures(routine: CommuteRoutine, isManualRefresh: Boolean) {
        departuresJob?.cancel()
        val requestId = ++departuresRequestId

        _uiState.update {
            if (isManualRefresh) it.copy(isRefreshingDepartures = true) else it.copy(departures = LiveDeparturesState.Loading)
        }

        departuresJob = viewModelScope.launch {
            getLiveDepartures(routine, previous = lastSnapshot).collect { state ->
                if (requestId != departuresRequestId) return@collect

                if (state is LiveDeparturesState.Loading) {
                    // Only the very first (non-refresh) load surfaces Loading to the UI —
                    // a manual refresh already set isRefreshingDepartures above and keeps
                    // the previous departures/state visible underneath it.
                    if (!isManualRefresh) {
                        _uiState.update { it.copy(departures = state) }
                    }
                    return@collect
                }

                if (state is LiveDeparturesState.Live) {
                    lastSnapshot = state.snapshot
                }
                _uiState.update { it.copy(departures = state, isRefreshingDepartures = false) }
            }
        }
    }
}
