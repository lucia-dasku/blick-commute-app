package se.blick.app.ui.screens.routinecreate

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.blick.app.data.repository.DirectionOption
import se.blick.app.data.repository.DirectionOptionsSource
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StopRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

private const val LOG_TAG = "RoutineCreateViewModel"

enum class RoutineCreateStep {
    STOP, TRANSPORT_MODE, DIRECTION, SCHEDULE
}

/**
 * Setup flow order per the product doc: stop -> transport mode -> line/direction ->
 * weekdays -> time window (+ name). `DirectionOptionsSource` conflates "line" and
 * "direction" into a single selectable [DirectionOption] already (see that interface's
 * doc comment on its live-departures-window limitation), so this wizard has one selection
 * step for both rather than two.
 *
 * Every network-backed step (stop search, direction lookup, save) distinguishes a
 * genuine "nothing here"/success result from an actual failure (network/server/
 * deserialization error), rethrows [CancellationException] rather than swallowing it, and
 * exposes only a boolean failure flag to the UI — never the raw exception message, class
 * name, or any hostname — which the UI turns into a fixed, friendly string. The real
 * exception is always logged via [Log.e] for developers. See the 2026-07-28 production
 * incident (a real backend outage that looked identical to "no stops found" because
 * errors were being silently swallowed into empty results) for why this distinction
 * matters everywhere in this file, not just in the one place it was first noticed.
 */
data class RoutineCreateUiState(
    val step: RoutineCreateStep = RoutineCreateStep.STOP,
    val siteQuery: String = "",
    val isSearching: Boolean = false,
    val siteResults: List<Site> = emptyList(),
    val searchFailed: Boolean = false,
    val selectedSite: Site? = null,
    val isLoadingDirections: Boolean = false,
    // A successful lookup that legitimately found zero lines/directions currently running
    // (see DirectionOptionsSource's live-departures-window limitation) — as distinct from...
    val directionsEmpty: Boolean = false,
    // ...a lookup that threw (network/server/deserialization failure).
    val directionsFailed: Boolean = false,
    val directionOptions: List<DirectionOption> = emptyList(),
    val availableTransportModes: List<TransportMode> = emptyList(),
    val selectedTransportMode: TransportMode? = null,
    val selectedDirection: DirectionOption? = null,
    val activeDays: Set<DayOfWeek> = emptySet(),
    val startTime: LocalTime = LocalTime.of(7, 0),
    val endTime: LocalTime = LocalTime.of(9, 0),
    val name: String = "",
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val hasSelectedDays: Boolean get() = activeDays.isNotEmpty()

    /** Same-day window only for this first version — no overnight (end-before-start) routines. */
    val isTimeRangeValid: Boolean get() = startTime.isBefore(endTime)

    val canSave: Boolean
        get() = selectedSite != null && selectedDirection != null &&
            hasSelectedDays && isTimeRangeValid && name.isNotBlank() && !isSaving
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class RoutineCreateViewModel @Inject constructor(
    private val stopRepository: StopRepository,
    private val directionOptionsSource: DirectionOptionsSource,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutineCreateUiState())
    val uiState: StateFlow<RoutineCreateUiState> = _uiState.asStateFlow()

    /**
     * `retryToken` exists purely so [retryStopSearch] can force [collectLatest] to re-run
     * the exact same query: `distinctUntilChanged()` compares the whole [SearchRequest],
     * so bumping the token makes an otherwise-identical resubmission look "different"
     * without weakening the dedup for ordinary typing.
     */
    private data class SearchRequest(val query: String, val retryToken: Int = 0)

    private val queryFlow = MutableStateFlow(SearchRequest(""))

    /** Once the user edits the suggested name themselves, stop overwriting it on re-selection. */
    private var nameManuallyEdited = false

    /**
     * Guards against a stale direction-lookup response (site A) overwriting a more recent
     * one (site B, or a retry of A) after the two race — [selectSite] used to launch each
     * lookup independently with no relationship to previous ones, so an old, slow request
     * completing after a newer one had already resolved could silently resurrect stale
     * options/errors or move the wizard forward with the wrong site's data. [directionsJob]
     * stops the old coroutine outright (saving the wasted work); [directionsRequestId] is
     * the actual correctness guarantee — checked before every state update inside the
     * lookup so a stale completion is a no-op even in the rare case cancellation doesn't
     * land before the old call's result arrives.
     */
    private var directionsJob: Job? = null
    private var directionsRequestId = 0

    private fun cancelInFlightDirectionsRequest() {
        directionsRequestId++
        directionsJob?.cancel()
        directionsJob = null
    }

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { request -> performSearch(request.query) }
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(siteResults = emptyList(), isSearching = false, searchFailed = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true, searchFailed = false) }
        try {
            val results = stopRepository.searchStops(query)
            _uiState.update { it.copy(siteResults = results, isSearching = false, searchFailed = false) }
        } catch (e: CancellationException) {
            // A newer query (or a retry) superseded this one (collectLatest) — not a real
            // failure, must propagate for structured concurrency to work.
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Stop search failed for query '$query'", e)
            _uiState.update { it.copy(siteResults = emptyList(), isSearching = false, searchFailed = true) }
        }
    }

    fun onSiteQueryChanged(query: String) {
        cancelInFlightDirectionsRequest()
        _uiState.update {
            it.copy(
                siteQuery = query,
                // A previous site selection/direction lookup (in progress, empty, or
                // failed) must never obscure a new search — see the 2026-07-28 review that
                // caught this: typing a new query used to leave the stale direction-error
                // UI on screen indefinitely, hiding the new search's own results.
                selectedSite = null,
                isLoadingDirections = false,
                directionsEmpty = false,
                directionsFailed = false,
                directionOptions = emptyList(),
                availableTransportModes = emptyList(),
                selectedTransportMode = null,
                selectedDirection = null,
            )
        }
        queryFlow.update { it.copy(query = query) }
    }

    /** Retries the current query even though it hasn't changed (see [SearchRequest]). */
    fun retryStopSearch() {
        queryFlow.update { it.copy(retryToken = it.retryToken + 1) }
    }

    fun selectSite(site: Site) {
        // No same-site early return here on purpose: after navigating back to STOP and
        // selecting the same stop again, the direction lookup must actually re-run (or at
        // least be re-attempted) rather than silently doing nothing — see the 2026-07-28
        // review. Re-selecting is cheap (one network call) and always correct; skipping it
        // was an incorrect optimization that made "back, then reselect" look broken.
        //
        // Cancel/invalidate whatever direction request was previously in flight (a
        // different site, or an earlier attempt at this same one via retryDirections)
        // BEFORE starting this one — see the class doc on directionsJob/directionsRequestId
        // for why both a cancel and a generation check are needed.
        cancelInFlightDirectionsRequest()
        val requestId = directionsRequestId
        _uiState.update {
            it.copy(
                selectedSite = site,
                selectedTransportMode = null,
                selectedDirection = null,
                directionOptions = emptyList(),
                availableTransportModes = emptyList(),
                directionsEmpty = false,
                directionsFailed = false,
                isLoadingDirections = true,
            )
        }
        directionsJob = viewModelScope.launch {
            try {
                val options = directionOptionsSource.getDirectionOptions(site.siteId)
                // A newer selectSite/retryDirections call superseded this one while it was
                // in flight — its result is stale even if cancellation didn't pre-empt it.
                if (requestId != directionsRequestId) return@launch
                if (options.isEmpty()) {
                    _uiState.update { it.copy(isLoadingDirections = false, directionsEmpty = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingDirections = false,
                            directionOptions = options,
                            availableTransportModes = options.map { option -> option.transportMode }.distinct(),
                            step = RoutineCreateStep.TRANSPORT_MODE,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId != directionsRequestId) return@launch
                Log.e(LOG_TAG, "Failed to load direction options for site ${site.siteId}", e)
                _uiState.update { it.copy(isLoadingDirections = false, directionsFailed = true) }
            }
        }
    }

    /**
     * Re-runs the direction lookup for the currently selected site. Used by both the
     * "empty" and "failed" direction states — the retry action is the same lookup either
     * way, only the message shown for each differs.
     */
    fun retryDirections() {
        _uiState.value.selectedSite?.let { site -> selectSite(site) }
    }

    fun selectTransportMode(mode: TransportMode) {
        _uiState.update {
            it.copy(selectedTransportMode = mode, selectedDirection = null, step = RoutineCreateStep.DIRECTION)
        }
    }

    fun selectDirection(direction: DirectionOption) {
        _uiState.update { current ->
            val suggestedName = listOfNotNull(
                direction.lineDesignation,
                direction.destinationLabel?.let { "→ $it" },
            ).joinToString(" ")
            current.copy(
                selectedDirection = direction,
                name = if (nameManuallyEdited) current.name else suggestedName,
                step = RoutineCreateStep.SCHEDULE,
            )
        }
    }

    fun toggleDay(day: DayOfWeek) {
        _uiState.update {
            val days = it.activeDays.toMutableSet()
            if (!days.add(day)) days.remove(day)
            it.copy(activeDays = days)
        }
    }

    fun setStartTime(time: LocalTime) {
        _uiState.update { it.copy(startTime = time) }
    }

    fun setEndTime(time: LocalTime) {
        _uiState.update { it.copy(endTime = time) }
    }

    fun setName(name: String) {
        nameManuallyEdited = true
        _uiState.update { it.copy(name = name) }
    }

    /** Returns true if it moved back a step; false if already at the first step (caller should exit). */
    fun back(): Boolean {
        val previous = when (_uiState.value.step) {
            RoutineCreateStep.STOP -> return false
            RoutineCreateStep.TRANSPORT_MODE -> RoutineCreateStep.STOP
            RoutineCreateStep.DIRECTION -> RoutineCreateStep.TRANSPORT_MODE
            RoutineCreateStep.SCHEDULE -> RoutineCreateStep.DIRECTION
        }
        _uiState.update { it.copy(step = previous) }
        return true
    }

    /**
     * Persists the routine. Re-entrant-safe (an already-in-flight save is a no-op, not a
     * second concurrent write) and never leaves [RoutineCreateUiState.isSaving] stuck on
     * `true` — success, failure, and cancellation all reset it. [onSaved] is only called
     * after the repository call actually completes successfully; a failure surfaces
     * [RoutineCreateUiState.saveFailed] instead (see [save]'s own caller for the retry
     * action, which is just calling [save] again — rebuilding the same [CommuteRoutine]
     * from current state is naturally idempotent-enough to retry).
     */
    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.isSaving) return
        val site = state.selectedSite ?: return
        val direction = state.selectedDirection ?: return
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.save(
                    CommuteRoutine(
                        name = state.name,
                        siteId = site.siteId,
                        siteName = site.name,
                        transportMode = direction.transportMode,
                        lineId = direction.lineId,
                        lineDesignation = direction.lineDesignation,
                        directionCode = direction.directionCode,
                        destinationLabel = direction.destinationLabel,
                        activeDays = state.activeDays,
                        startTime = state.startTime,
                        endTime = state.endTime,
                    ),
                )
                _uiState.update { it.copy(isSaving = false, saveFailed = false) }
                onSaved()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to save routine", e)
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }
}
