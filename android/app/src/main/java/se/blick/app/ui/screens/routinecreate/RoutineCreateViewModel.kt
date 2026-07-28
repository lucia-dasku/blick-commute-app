package se.blick.app.ui.screens.routinecreate

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

enum class RoutineCreateStep {
    STOP, TRANSPORT_MODE, DIRECTION, SCHEDULE
}

/**
 * Setup flow order per the product doc: stop -> transport mode -> line/direction ->
 * weekdays -> time window (+ name). `DirectionOptionsSource` conflates "line" and
 * "direction" into a single selectable [DirectionOption] already (see that interface's
 * doc comment on its live-departures-window limitation), so this wizard has one selection
 * step for both rather than two.
 */
data class RoutineCreateUiState(
    val step: RoutineCreateStep = RoutineCreateStep.STOP,
    val siteQuery: String = "",
    val isSearching: Boolean = false,
    val siteResults: List<Site> = emptyList(),
    // Non-null only when the last search attempt threw (network/server failure), as
    // distinct from a search that succeeded but matched nothing. Shown to the user
    // verbatim — this app isn't shipped yet, and a real message here is far more useful
    // for diagnosing setup problems (wrong backend URL, unreachable server, etc.) than a
    // misleading "No stops found".
    val searchErrorMessage: String? = null,
    val selectedSite: Site? = null,
    val isLoadingDirections: Boolean = false,
    val directionsError: Boolean = false,
    val directionOptions: List<DirectionOption> = emptyList(),
    val availableTransportModes: List<TransportMode> = emptyList(),
    val selectedTransportMode: TransportMode? = null,
    val selectedDirection: DirectionOption? = null,
    val activeDays: Set<DayOfWeek> = emptySet(),
    val startTime: LocalTime = LocalTime.of(7, 0),
    val endTime: LocalTime = LocalTime.of(9, 0),
    val name: String = "",
    val isSaving: Boolean = false,
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

    private val queryFlow = MutableStateFlow("")

    /** Once the user edits the suggested name themselves, stop overwriting it on re-selection. */
    private var nameManuallyEdited = false

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update {
                            it.copy(siteResults = emptyList(), isSearching = false, searchErrorMessage = null)
                        }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearching = true, searchErrorMessage = null) }
                    try {
                        val results = stopRepository.searchStops(query)
                        _uiState.update { it.copy(siteResults = results, isSearching = false) }
                    } catch (e: CancellationException) {
                        // A newer query superseded this one (collectLatest) — not a real
                        // failure, must propagate for structured concurrency to work.
                        throw e
                    } catch (e: Exception) {
                        Log.e("RoutineCreateViewModel", "Stop search failed for query '$query'", e)
                        _uiState.update {
                            it.copy(
                                siteResults = emptyList(),
                                isSearching = false,
                                searchErrorMessage = e.message ?: e::class.simpleName ?: "Unknown error",
                            )
                        }
                    }
                }
        }
    }

    fun onSiteQueryChanged(query: String) {
        _uiState.update { it.copy(siteQuery = query) }
        queryFlow.value = query
    }

    fun selectSite(site: Site) {
        if (_uiState.value.selectedSite?.siteId == site.siteId) return
        _uiState.update {
            it.copy(
                selectedSite = site,
                selectedTransportMode = null,
                selectedDirection = null,
                directionOptions = emptyList(),
                availableTransportModes = emptyList(),
                directionsError = false,
                isLoadingDirections = true,
            )
        }
        viewModelScope.launch {
            val options = runCatching { directionOptionsSource.getDirectionOptions(site.siteId) }.getOrNull()
            if (options.isNullOrEmpty()) {
                _uiState.update { it.copy(isLoadingDirections = false, directionsError = true) }
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
        }
    }

    /** Lets the user retry after an empty/failed direction lookup without re-typing the search. */
    fun retryDirections() {
        _uiState.value.selectedSite?.let { site ->
            _uiState.update { it.copy(selectedSite = null) }
            selectSite(site)
        }
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

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val site = state.selectedSite ?: return
        val direction = state.selectedDirection ?: return
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
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
            _uiState.update { it.copy(isSaving = false) }
            onSaved()
        }
    }
}
