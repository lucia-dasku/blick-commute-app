package se.blick.app.ui.screens.routinedetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private const val LOG_TAG = "RoutineDetailsViewModel"

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
    /** Guards overlapping enable/disable taps; a failure leaves [RoutineDetailsUiState.routine]
     * at whatever the write actually left in storage — never a value the UI merely hoped for. */
    val isTogglingEnabled: Boolean = false,
    val enabledActionFailed: Boolean = false,
    /** Shared guard for both "pause today" and "resume today" — they are mutually exclusive
     * actions on the same field, so only one can be in flight at a time. */
    val isTogglingPause: Boolean = false,
    val pauseActionFailed: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteFailed: Boolean = false,
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
            val loaded = routineRepository.getById(routineId)
            if (loaded == null) {
                _uiState.update { it.copy(isRoutineLoading = false, routineNotFound = true) }
            } else {
                val routine = clearExpiredPauseIfNeeded(loaded)
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

    /** Clears a [pausedDate][CommuteRoutine.pausedDate] that is strictly before today (an
     * expired "pause for today"), leaving today's date or any (never-scheduled, but handled
     * defensively) future date untouched. A no-op write-wise when there's nothing to clear —
     * [RoutineRepository.clearPause] itself is also a no-op if already null, so this can
     * never repeatedly write the same state. */
    private suspend fun clearExpiredPauseIfNeeded(routine: CommuteRoutine): CommuteRoutine {
        val pausedDate = routine.pausedDate ?: return routine
        val today = LocalDate.now(clock)
        if (!pausedDate.isBefore(today)) return routine
        routineRepository.clearPause(routine.id)
        return routine.copy(pausedDate = null)
    }

    /** Manual refresh action for the "Next departures" section. A no-op if the routine
     * itself never loaded (nothing to refresh departures for). */
    fun refresh() {
        val routine = _uiState.value.routine ?: return
        loadDepartures(routine, isManualRefresh = true)
    }

    /**
     * Re-checks the routine from storage. Called after returning from a successful edit (see
     * [se.blick.app.ui.navigation.BlickNavHost], which signals this via the previous back
     * stack entry's `SavedStateHandle` rather than this ViewModel reaching into navigation
     * itself) so this already-alive screen picks up the new name/station/line/schedule
     * without a periodic background refresh loop. A no-op while the initial load from
     * [init] hasn't resolved yet — that load already reflects the latest state, and racing
     * it here would just be a redundant read.
     *
     * If the departure-relevant identity of the routine changed (site/line/direction/mode),
     * a fresh departures fetch is triggered for the new configuration — reusing
     * [loadDepartures]'s existing request-generation-token guard, so a slow, now-stale fetch
     * for the OLD configuration can never overwrite the new one.
     */
    fun reload() {
        if (_uiState.value.isRoutineLoading) return
        viewModelScope.launch {
            val loaded = routineRepository.getById(routineId) ?: return@launch
            val fresh = clearExpiredPauseIfNeeded(loaded)
            val previous = _uiState.value.routine
            val departureConfigChanged = previous == null ||
                previous.siteId != fresh.siteId ||
                previous.lineId != fresh.lineId ||
                previous.directionCode != fresh.directionCode ||
                previous.transportMode != fresh.transportMode
            _uiState.update {
                it.copy(
                    routine = fresh,
                    isPausedToday = fresh.pausedDate == LocalDate.now(clock),
                )
            }
            if (departureConfigChanged) {
                loadDepartures(fresh, isManualRefresh = false)
            }
        }
    }

    /**
     * Toggles [CommuteRoutine.enabled] only — never touches `pausedDate` or any other field
     * (see [RoutineRepository.setEnabled]'s doc). Guarded against overlapping taps; on
     * failure the local [RoutineDetailsUiState.routine] is left exactly as it was (the write
     * never happened), paired with a friendly, retryable [RoutineDetailsUiState.enabledActionFailed].
     */
    fun toggleEnabled() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingEnabled) return
        val newEnabled = !routine.enabled
        _uiState.update { it.copy(isTogglingEnabled = true, enabledActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.setEnabled(routine.id, newEnabled)
                _uiState.update {
                    it.copy(isTogglingEnabled = false, routine = it.routine?.copy(enabled = newEnabled))
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingEnabled = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to toggle enabled for routine ${routine.id}", e)
                _uiState.update { it.copy(isTogglingEnabled = false, enabledActionFailed = true) }
            }
        }
    }

    /** Sets `pausedDate` to today (see [RoutineRepository.pauseForDate]) — independent of
     * [CommuteRoutine.enabled], never toggles it. Guarded against overlapping taps with
     * [resumeToday] (they're mutually exclusive actions on the same field). */
    fun pauseToday() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingPause) return
        val today = LocalDate.now(clock)
        _uiState.update { it.copy(isTogglingPause = true, pauseActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.pauseForDate(routine.id, today)
                _uiState.update {
                    it.copy(isTogglingPause = false, isPausedToday = true, routine = it.routine?.copy(pausedDate = today))
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingPause = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to pause routine ${routine.id} for today", e)
                _uiState.update { it.copy(isTogglingPause = false, pauseActionFailed = true) }
            }
        }
    }

    /** Clears `pausedDate` (see [RoutineRepository.clearPause]) — independent of
     * [CommuteRoutine.enabled], never toggles it. */
    fun resumeToday() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingPause) return
        _uiState.update { it.copy(isTogglingPause = true, pauseActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.clearPause(routine.id)
                _uiState.update {
                    it.copy(isTogglingPause = false, isPausedToday = false, routine = it.routine?.copy(pausedDate = null))
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingPause = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to resume routine ${routine.id}", e)
                _uiState.update { it.copy(isTogglingPause = false, pauseActionFailed = true) }
            }
        }
    }

    /**
     * Deletes the routine. The confirmation dialog itself is UI-local state (see
     * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]) — this is only called
     * once the user has actually confirmed. Guarded against a repeated confirm tap firing a
     * second delete while the first is still in flight. [onDeleted] is only invoked after
     * the repository call actually succeeds, so the caller can navigate back to the list;
     * on failure the screen stays put with a friendly, retryable [RoutineDetailsUiState.deleteFailed].
     */
    fun deleteRoutine(onDeleted: () -> Unit) {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isDeleting) return
        _uiState.update { it.copy(isDeleting = true, deleteFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.delete(routine.id)
                _uiState.update { it.copy(isDeleting = false) }
                onDeleted()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isDeleting = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to delete routine ${routine.id}", e)
                _uiState.update { it.copy(isDeleting = false, deleteFailed = true) }
            }
        }
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
