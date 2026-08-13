package se.blick.app.ui.screens.routinecreate

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.repository.DirectionOption
import se.blick.app.data.repository.DirectionOptionsSource
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StopRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.RoutineDurationValidationResult
import se.blick.app.domain.usecase.RoutineDurationValidator
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.ui.navigation.Routes
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import se.blick.app.billing.FreePremiumEntitlementRepository
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.repository.RoutineScheduleOverlapException

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
    val hasPremium: Boolean = false,
    val destinationQuery: String = "",
    val destinationResults: List<JourneyLocation> = emptyList(),
    val selectedDestination: JourneyLocation? = null,
    val journeyOrigin: JourneyLocation? = null,
    val isSearchingDestination: Boolean = false,
    val destinationSearchFailed: Boolean = false,
    val isResolvingJourneyOrigin: Boolean = false,
    val journeyOriginResolutionFailed: Boolean = false,
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
    /** True once [RoutineRepository.save] has genuinely succeeded but the immediately-following
     * [RoutineScheduler.scheduleActivation] call failed — see [RoutineCreateViewModel.save]'s own
     * doc on why this is deliberately NOT the same signal as [saveFailed]: the routine IS saved,
     * only its notification scheduling needs a retry (see [RoutineCreateViewModel.retryScheduling]).
     * Mutually exclusive with [saveFailed] in practice — a genuine Room failure never reaches the
     * scheduling call at all. */
    val schedulingFailed: Boolean = false,
    /** Guards [RoutineCreateViewModel.retryScheduling] against an overlapping second tap, exactly
     * like [isSaving] guards [RoutineCreateViewModel.save]. */
    val isRetryingScheduling: Boolean = false,
    /** True when the most recent [RoutineCreateViewModel.save] attempt was blocked because this
     * routine, combined with every other enabled routine active on one of its days, would
     * exceed [se.blick.app.domain.usecase.MAX_DAILY_ACTIVE_MINUTES] — see
     * [RoutineDurationValidator]. Unlike [saveFailed] (which only clears on the next save
     * attempt), this is also cleared as soon as the user changes the start time, end time, or
     * active days — the exact inputs this validation depends on — so a stale error can never
     * linger after the user has already adjusted the schedule to fix it. */
    val durationLimitExceeded: Boolean = false,
    val scheduleOverlap: Boolean = false,
    /** True when this screen was opened to edit an existing routine (see
     * [RoutineCreateViewModel]'s edit-mode support) rather than to create a new one. */
    val isEditMode: Boolean = false,
    val isLoadingExistingRoutine: Boolean = false,
    /** Edit mode only: the routineId from navigation didn't resolve to a saved routine. */
    val existingRoutineNotFound: Boolean = false,
    /** Create mode only: a routine already exists — see the first-beta one-routine limit. */
    val oneRoutineLimitReached: Boolean = false,
    /** Mirrors `AppSettingsDataStore.hasSeenNotificationRationale` — true once the production
     * notification-permission rationale has been shown once (regardless of the user's answer),
     * so [se.blick.app.ui.notification.rememberNotificationPermissionGate] never asks again. */
    val hasSeenNotificationRationale: Boolean = false,
) {
    val hasSelectedDays: Boolean get() = activeDays.isNotEmpty()

    /** Only an exact start-equals-end selection is invalid — an end time at or before the start
     * is a valid overnight (midnight-crossing) window, resolved onto the following date by
     * [se.blick.app.scheduling.NextOccurrenceCalculator],
     * [se.blick.app.domain.usecase.RoutineDurationValidator], and
     * [se.blick.app.domain.usecase.RoutineScheduleOverlapValidator] alike. */
    val isTimeRangeValid: Boolean get() = startTime != endTime

    val isExactDestination: Boolean get() = selectedDestination != null

    val canContinueFromStops: Boolean
        get() = selectedSite != null && when {
            isExactDestination -> hasPremium && journeyOrigin != null &&
                journeyOrigin.id != selectedDestination?.id && !isResolvingJourneyOrigin
            destinationQuery.isNotBlank() -> false
            else -> !isLoadingDirections && directionOptions.isNotEmpty()
        }

    val canSave: Boolean
        get() = selectedSite != null &&
            (if (isExactDestination) hasPremium && journeyOrigin != null &&
                journeyOrigin.id != selectedDestination?.id else selectedDirection != null) &&
            hasSelectedDays && isTimeRangeValid && name.isNotBlank() && !isSaving &&
            !oneRoutineLimitReached
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class RoutineCreateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stopRepository: StopRepository,
    private val directionOptionsSource: DirectionOptionsSource,
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val journeyRepository: JourneyRepository,
    private val entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
) : ViewModel() {

    /** Present only when this screen was reached via [Routes.RoutineEdit] — absent (null)
     * for [Routes.RoutineCreate]. This is the ONLY thing that distinguishes edit mode from
     * create mode; everything else below reuses the exact same wizard/state/validation. */
    private val editingRoutineId: String? = savedStateHandle.get<String>(Routes.RoutineEdit.ARG_ROUTINE_ID)

    private val _uiState = MutableStateFlow(RoutineCreateUiState())
    val uiState: StateFlow<RoutineCreateUiState> = _uiState.asStateFlow()

    /** Captured once, at edit-mode load time: the fields editing must preserve rather than
     * let the wizard reset (id, enabled, pausedDate) — see [save]. Null in create mode. */
    private var originalRoutine: CommuteRoutine? = null

    /**
     * The direction the currently in-flight/most-recent direction fetch should try to
     * preserve, or null for a plain "start fresh" fetch. Set by [fetchDirections]'s callers:
     * [selectSite] always passes null (a station change must always clear mode/direction —
     * see [onSiteQueryChanged]'s doc); the edit-mode loader passes the saved routine's own
     * line/direction so it survives a fetch against the (time-window-limited) live feed;
     * [retryDirections] simply re-uses whichever of the two was last requested, so retrying
     * never surprises the user by discarding an edit-mode pre-fill.
     */
    private var activePreselect: DirectionOption? = null

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
    private var destinationJob: Job? = null
    private var journeyOriginJob: Job? = null
    private var advanceAfterDirectionsLoad = true

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
        viewModelScope.launch {
            val seen = appSettingsDataStore.settings.first().hasSeenNotificationRationale
            _uiState.update { it.copy(hasSeenNotificationRationale = seen) }
        }
        val routineId = editingRoutineId
        if (routineId != null) {
            _uiState.update { it.copy(isEditMode = true, isLoadingExistingRoutine = true) }
            viewModelScope.launch {
                entitlementRepository.entitlement.collect { entitlement ->
                    _uiState.update { it.copy(hasPremium = entitlement.hasPremiumAccess) }
                }
            }
            viewModelScope.launch { loadExistingRoutine(routineId) }
        } else {
            // First-beta one-routine limit (see RoutineListScreen for the matching list-side
            // guard) — both read the same RoutineRepository, so they can't disagree.
            viewModelScope.launch {
                entitlementRepository.entitlement.collect { entitlement ->
                    val hasPremium = entitlement.hasPremiumAccess
                    val hasFreeLineRoutine = routineRepository.observeAll().first()
                        .any { routine -> routine.type == RoutineType.LINE_DIRECTION }
                    _uiState.update {
                        it.copy(
                            hasPremium = hasPremium,
                            oneRoutineLimitReached = hasFreeLineRoutine && !hasPremium,
                            destinationQuery = if (hasPremium) it.destinationQuery else "",
                            destinationResults = if (hasPremium) it.destinationResults else emptyList(),
                            selectedDestination = if (hasPremium) it.selectedDestination else null,
                            journeyOrigin = if (hasPremium) it.journeyOrigin else null,
                        )
                    }
                    if (hasPremium) {
                        _uiState.value.selectedSite
                            ?.takeIf { _uiState.value.journeyOrigin == null }
                            ?.let(::resolveJourneyOrigin)
                    }
                }
            }
        }
    }

    private suspend fun loadExistingRoutine(routineId: String) {
        val existing = routineRepository.getById(routineId)
        if (existing == null) {
            _uiState.update { it.copy(isLoadingExistingRoutine = false, existingRoutineNotFound = true) }
            return
        }
        originalRoutine = existing
        // The user's existing name is treated as already deliberately set — a later
        // automatic direction-based suggestion (see selectDirection) must not clobber it.
        nameManuallyEdited = true
        _uiState.update {
            it.copy(
                isLoadingExistingRoutine = false,
                siteQuery = existing.siteName,
                activeDays = existing.activeDays,
                startTime = existing.startTime,
                endTime = existing.endTime,
                name = existing.name,
            )
        }
        val syntheticSite = Site(
            siteId = existing.siteId,
            name = existing.siteName,
            note = null,
            lat = null,
            lon = null,
            stopAreaIds = listOf(existing.siteId),
        )
        if (existing.type == RoutineType.EXACT_DESTINATION) {
            val journeyOrigin = existing.journeyOriginId?.let { id ->
                JourneyLocation(id = id, name = existing.journeyOriginName ?: existing.siteName)
            }
            val journeyDestination = existing.journeyDestinationId?.let { id ->
                JourneyLocation(id = id, name = existing.journeyDestinationName.orEmpty())
            }
            _uiState.update {
                it.copy(
                    selectedSite = syntheticSite,
                    journeyOrigin = journeyOrigin,
                    destinationQuery = journeyDestination?.name.orEmpty(),
                    selectedDestination = journeyDestination,
                    selectedTransportMode = null,
                    selectedDirection = null,
                    directionOptions = emptyList(),
                    availableTransportModes = emptyList(),
                    step = RoutineCreateStep.SCHEDULE,
                )
            }
            return
        }
        val savedDirection = existing.lineId?.let { lineId ->
            DirectionOption(
                lineId = lineId,
                lineDesignation = existing.lineDesignation.orEmpty(),
                transportMode = existing.transportMode,
                directionCode = existing.directionCode,
                destinationLabel = existing.destinationLabel,
            )
        }
        activePreselect = savedDirection
        fetchDirections(syntheticSite, preselect = savedDirection)
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
        journeyOriginJob?.cancel()
        _uiState.update {
            it.copy(
                siteQuery = query,
                // A previous site selection/direction lookup (in progress, empty, or
                // failed) must never obscure a new search — see the 2026-07-28 review that
                // caught this: typing a new query used to leave the stale direction-error
                // UI on screen indefinitely, hiding the new search's own results.
                selectedSite = null,
                journeyOrigin = null,
                isResolvingJourneyOrigin = false,
                journeyOriginResolutionFailed = false,
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
        // Explicit user-initiated station change: always start fresh — a previously
        // preselected mode/direction (from edit mode, or from a prior stop) is no longer
        // valid for a different stop. See onSiteQueryChanged's doc for the same rule.
        selectSite(site, advanceAfterLoad = true)
    }

    fun selectOrigin(site: Site) {
        selectSite(site, advanceAfterLoad = false)
    }

    private fun selectSite(site: Site, advanceAfterLoad: Boolean) {
        activePreselect = null
        _uiState.update { current ->
            val suggestedName = current.selectedDestination?.let { "${site.name} → ${it.name}" }
            current.copy(
                siteQuery = site.name,
                siteResults = emptyList(),
                name = if (!nameManuallyEdited && suggestedName != null) suggestedName else current.name,
            )
        }
        fetchDirections(site, preselect = null, advanceAfterLoad = advanceAfterLoad)
        resolveJourneyOrigin(site)
    }

    fun onDestinationQueryChanged(query: String) {
        if (!_uiState.value.hasPremium) return
        destinationJob?.cancel()
        _uiState.update {
            it.copy(
                destinationQuery = query,
                selectedDestination = null,
                destinationResults = emptyList(),
                destinationSearchFailed = false,
                isSearchingDestination = query.isNotBlank(),
            )
        }
        if (query.isBlank()) return
        destinationJob = viewModelScope.launch {
            delay(300)
            try {
                val results = journeyRepository.searchLocations(query)
                _uiState.update {
                    it.copy(destinationResults = results, isSearchingDestination = false, destinationSearchFailed = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Journey destination search failed", e)
                _uiState.update {
                    it.copy(destinationResults = emptyList(), isSearchingDestination = false, destinationSearchFailed = true)
                }
            }
        }
    }

    fun selectDestination(destination: JourneyLocation) {
        _uiState.update { current ->
            val suggestedName = "${current.selectedSite?.name.orEmpty()} → ${destination.name}".trim()
            current.copy(
                destinationQuery = destination.name,
                destinationResults = emptyList(),
                selectedDestination = destination,
                name = if (nameManuallyEdited) current.name else suggestedName,
            )
        }
    }

    private fun resolveJourneyOrigin(site: Site) {
        if (!_uiState.value.hasPremium) return
        journeyOriginJob?.cancel()
        _uiState.update { it.copy(journeyOrigin = null, isResolvingJourneyOrigin = true, journeyOriginResolutionFailed = false) }
        journeyOriginJob = viewModelScope.launch {
            try {
                val locations = journeyRepository.searchLocations(site.name)
                val normalizedSiteName = site.name.substringBefore(',').trim()
                val match = locations.firstOrNull {
                    it.name.substringBefore(',').trim().equals(normalizedSiteName, ignoreCase = true)
                } ?: locations.firstOrNull()
                _uiState.update {
                    it.copy(
                        journeyOrigin = match,
                        isResolvingJourneyOrigin = false,
                        journeyOriginResolutionFailed = match == null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not resolve Journey Planner origin for ${site.name}", e)
                _uiState.update { it.copy(isResolvingJourneyOrigin = false, journeyOriginResolutionFailed = true) }
            }
        }
    }

    /**
     * Re-runs the direction lookup for the currently selected site, preserving whatever
     * [activePreselect] the last attempt used. Used by both the "empty" and "failed"
     * direction states — the retry action is the same lookup either way, only the message
     * shown for each differs — and by a failed edit-mode initial load, so retrying never
     * discards the pre-filled mode/direction.
     */
    fun retryDirections() {
        _uiState.value.selectedSite?.let { site ->
            fetchDirections(site, preselect = activePreselect, advanceAfterLoad = advanceAfterDirectionsLoad)
        }
    }

    /**
     * Shared direction-lookup engine for both plain site selection (creation) and the
     * edit-mode pre-fill. [preselect], when non-null, is the routine's already-saved
     * line/direction: if the live feed's current options include a match (by line id +
     * direction code + transport mode — see [DirectionOption.matchesForPreselect]), that
     * option is selected automatically; if the feed doesn't currently have it running (see
     * [DirectionOptionsSource]'s live-departures-window limitation) it is still shown, added
     * to the top of the list, so an edit never silently loses the user's existing selection
     * just because it isn't live at the moment they happen to be editing.
     *
     * No same-site early return on purpose — see the retained 2026-07-28 review note: after
     * navigating back to STOP and reselecting the same stop, the lookup must actually
     * re-run. Cancel/invalidate any previous in-flight request first — see the class doc on
     * [directionsJob]/[directionsRequestId].
     */
    private fun fetchDirections(site: Site, preselect: DirectionOption?, advanceAfterLoad: Boolean = true) {
        cancelInFlightDirectionsRequest()
        advanceAfterDirectionsLoad = advanceAfterLoad
        val requestId = directionsRequestId
        _uiState.update {
            it.copy(
                selectedSite = site,
                selectedTransportMode = if (preselect == null) null else it.selectedTransportMode,
                selectedDirection = if (preselect == null) null else it.selectedDirection,
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
                if (options.isEmpty() && preselect == null) {
                    _uiState.update { it.copy(isLoadingDirections = false, directionsEmpty = true) }
                    return@launch
                }
                val hasPreselectLive = preselect != null && options.any { it.matchesForPreselect(preselect) }
                val effectiveOptions = when {
                    preselect == null -> options
                    hasPreselectLive -> options
                    else -> listOf(preselect) + options
                }
                val selected = preselect?.let { target -> effectiveOptions.find { it.matchesForPreselect(target) } }
                _uiState.update {
                    it.copy(
                        isLoadingDirections = false,
                        directionOptions = effectiveOptions,
                        availableTransportModes = effectiveOptions.map { option -> option.transportMode }.distinct(),
                        selectedTransportMode = selected?.transportMode ?: it.selectedTransportMode,
                        selectedDirection = selected ?: it.selectedDirection,
                        step = when {
                            preselect != null -> RoutineCreateStep.SCHEDULE
                            advanceAfterLoad -> RoutineCreateStep.TRANSPORT_MODE
                            else -> it.step
                        },
                    )
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

    fun selectTransportMode(mode: TransportMode) {
        _uiState.update {
            it.copy(selectedTransportMode = mode, selectedDirection = null, step = RoutineCreateStep.DIRECTION)
        }
    }

    fun continueFromStops() {
        if (!_uiState.value.canContinueFromStops) return
        _uiState.update {
            it.copy(step = if (it.isExactDestination) RoutineCreateStep.SCHEDULE else RoutineCreateStep.TRANSPORT_MODE)
        }
    }

    fun selectDirection(direction: DirectionOption) {
        _uiState.update { current ->
            // "{chosen stop} → {direction}" -- the line number is deliberately left out here,
            // since every place this name is shown (the routine list, Routine Details, this
            // wizard's own name field) already shows it via the colored LineBadge right next
            // to it; repeating it as text here would duplicate what the badge already says.
            val suggestedName = listOfNotNull(
                current.selectedSite?.name,
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
            it.copy(activeDays = days, durationLimitExceeded = false, scheduleOverlap = false)
        }
    }

    fun setStartTime(time: LocalTime) {
        _uiState.update {
            it.copy(
                startTime = time,
                endTime = if (time >= it.endTime) time.plusHours(1) else it.endTime,
                durationLimitExceeded = false,
                scheduleOverlap = false,
            )
        }
    }

    fun setEndTime(time: LocalTime) {
        _uiState.update { it.copy(endTime = time, durationLimitExceeded = false, scheduleOverlap = false) }
    }

    fun setName(name: String) {
        nameManuallyEdited = true
        _uiState.update { it.copy(name = name) }
    }

    /** Records that the production notification-permission rationale (see
     * [se.blick.app.ui.notification.rememberNotificationPermissionGate]) has now been shown
     * once, so it is never shown again regardless of the user's answer. Updates local state
     * immediately (optimistic) so a second save attempt in the same screen session can never
     * re-trigger it even before the DataStore write below completes. */
    fun markNotificationRationaleSeen() {
        _uiState.update { it.copy(hasSeenNotificationRationale = true) }
        viewModelScope.launch { appSettingsDataStore.setHasSeenNotificationRationale(true) }
    }

    /** Returns true if it moved back a step; false if already at the first step (caller should exit). */
    fun back(): Boolean {
        val previous = when (_uiState.value.step) {
            RoutineCreateStep.STOP -> return false
            RoutineCreateStep.TRANSPORT_MODE -> RoutineCreateStep.STOP
            RoutineCreateStep.DIRECTION -> RoutineCreateStep.TRANSPORT_MODE
            RoutineCreateStep.SCHEDULE -> if (_uiState.value.isExactDestination) {
                RoutineCreateStep.STOP
            } else {
                RoutineCreateStep.DIRECTION
            }
        }
        _uiState.update { it.copy(step = previous) }
        return true
    }

    /**
     * Persists the routine. Re-entrant-safe (an already-in-flight save is a no-op, not a
     * second concurrent write) and never leaves [RoutineCreateUiState.isSaving] stuck on
     * `true` — success, failure, and cancellation all reset it. [onSaved] is only called
     * after [routineRepository] actually accepts the write; a failure THERE (Room, or the
     * duration validation before it) surfaces [RoutineCreateUiState.saveFailed] instead (see
     * [save]'s own caller for the retry action, which is just calling [save] again —
     * rebuilding the same [CommuteRoutine] from current state is naturally idempotent-enough
     * to retry).
     *
     * **Persistence succeeding and scheduling succeeding are two different results.** Once
     * [routineRepository.save] accepts the write, this routine IS saved — a SUBSEQUENT
     * [routineScheduler.scheduleActivation] failure (its own, separate try/catch below) must
     * never retroactively surface [RoutineCreateUiState.saveFailed] for data that is already
     * durably stored. [originalRoutine] is set to [toSave] the moment the Room write succeeds
     * — in CREATE mode this is what stops a later [retryScheduling] (or an ordinary [save]
     * retry after nothing worse than a scheduling failure) from generating a brand new
     * [java.util.UUID] and inserting a second row: the next call's `existing?.id` is now this
     * same id, exactly like edit mode has always reused it.
     *
     * A scheduling failure here does NOT call [onSaved] — [RoutineCreateUiState.schedulingFailed]
     * is set instead, so the screen stays put and offers [retryScheduling], which targets only
     * the already-saved [toSave] (never [routineRepository.save] again). [onSaved] still runs
     * once scheduling succeeds, whether that's on this first attempt or after a [retryScheduling]
     * call. If the user navigates away without retrying,
     * [se.blick.app.scheduling.NotificationRecoveryCoordinator]'s own `onAppStart` remains the
     * fallback — it unconditionally reconciles every enabled routine's scheduling on the next
     * app start regardless of whether this call's own attempt succeeded.
     *
     * In edit mode [originalRoutine] already reuses its own `id`/`enabled`/`pausedDate` from
     * the start (see [loadExistingRoutine]) so the underlying repository upsert-by-id replaces
     * the same row rather than inserting a second routine, and so editing never silently resets
     * whether the routine is enabled/paused. In create mode,
     * [RoutineCreateUiState.oneRoutineLimitReached] blocks saving outright (defence in depth
     * alongside [RoutineCreateUiState.canSave] and the list screen's own guard — see the class
     * doc on [editingRoutineId]).
     *
     * Also re-validates [toSave]'s active-window duration against every other currently
     * enabled routine via [RoutineDurationValidator] — see [RoutineCreateUiState.durationLimitExceeded]
     * — BEFORE writing anything to [routineRepository] or [routineScheduler]: an edit
     * excludes [originalRoutine]'s own stored id from the comparison (via
     * [RoutineDurationValidator.validate]'s `proposedRoutineId`), so an edited routine never
     * counts its own pre-edit version twice.
     */
    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.isSaving) return
        val site = state.selectedSite ?: return
        val direction = state.selectedDirection
        val journeyOrigin = state.journeyOrigin
        val journeyDestination = state.selectedDestination
        if (!state.canSave) return
        if (!state.isEditMode && state.oneRoutineLimitReached) return

        _uiState.update { it.copy(isSaving = true, saveFailed = false, durationLimitExceeded = false) }
        viewModelScope.launch {
            try {
                val existing = originalRoutine
                val toSave = if (journeyDestination != null && journeyOrigin != null) {
                    CommuteRoutine(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = state.name,
                        siteId = site.siteId,
                        siteName = site.name,
                        transportMode = TransportMode.UNKNOWN,
                        lineId = null,
                        lineDesignation = null,
                        directionCode = null,
                        destinationLabel = null,
                        activeDays = state.activeDays,
                        startTime = state.startTime,
                        endTime = state.endTime,
                        enabled = existing?.enabled ?: true,
                        pausedDate = existing?.pausedDate,
                        type = RoutineType.EXACT_DESTINATION,
                        journeyOriginId = journeyOrigin.id,
                        journeyOriginName = journeyOrigin.name,
                        journeyDestinationId = journeyDestination.id,
                        journeyDestinationName = journeyDestination.name,
                        allowedJourneyTransportModes = existing?.allowedJourneyTransportModes
                            ?: se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES,
                    )
                } else {
                    val selectedDirection = direction ?: return@launch
                    CommuteRoutine(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = state.name,
                        siteId = site.siteId,
                        siteName = site.name,
                        transportMode = selectedDirection.transportMode,
                        lineId = selectedDirection.lineId,
                        lineDesignation = selectedDirection.lineDesignation,
                        directionCode = selectedDirection.directionCode,
                        destinationLabel = selectedDirection.destinationLabel,
                        activeDays = state.activeDays,
                        startTime = state.startTime,
                        endTime = state.endTime,
                        enabled = existing?.enabled ?: true,
                        pausedDate = existing?.pausedDate,
                    )
                }

                val otherRoutines = routineRepository.observeAll().first()
                val durationValidation = RoutineDurationValidator.validate(
                    proposedRoutineId = toSave.id,
                    proposedStartTime = toSave.startTime,
                    proposedEndTime = toSave.endTime,
                    proposedActiveDays = toSave.activeDays,
                    proposedEnabled = toSave.enabled,
                    existingRoutines = otherRoutines,
                )
                if (durationValidation is RoutineDurationValidationResult.ExceedsDailyLimit) {
                    _uiState.update { it.copy(isSaving = false, durationLimitExceeded = true) }
                    return@launch
                }

                routineRepository.save(toSave)
                // Once Room accepts the write, this routine IS saved -- persistence and
                // scheduling are two different results (see the class doc below), so nothing
                // past this point may cause a later save() retry to insert a second routine
                // with a fresh UUID. Recording toSave as originalRoutine (already the exact
                // mechanism edit mode uses to keep reusing the same id) makes create mode do
                // the same from here on: id/enabled/pausedDate above become `existing`'s next
                // time, whether the very next call is a genuine retry or an ordinary re-save.
                originalRoutine = toSave
                _uiState.update { it.copy(isSaving = false, saveFailed = false, schedulingFailed = false) }

                // Schedules (or, for an edit, replaces) this routine's next active-window
                // activation — see RoutineScheduler.scheduleActivation's own doc on why this
                // is safe to call unconditionally here even for a disabled routine (it cancels
                // any existing scheduled work instead). Deliberately its OWN try/catch, separate
                // from the Room write above: scheduling is a secondary side effect of an already-
                // successful save, so its failure must never retroactively make this look like a
                // save failure -- see the class doc on why this instead surfaces schedulingFailed
                // and keeps the user on this screen with a retry action, rather than silently
                // relying only on the next app start to notice.
                try {
                    routineScheduler.scheduleActivation(toSave)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Routine ${toSave.id} was saved but scheduling its activation failed", e)
                    _uiState.update { it.copy(schedulingFailed = true) }
                    // Best-effort widget attempt even though scheduling failed -- the routine's
                    // enabled/paused state (which the widget also reflects) is still genuinely
                    // correct, only its WorkManager activation needs a retry (see
                    // runWidgetUpdateSafely's own doc).
                    runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
                    // Deliberately does NOT call onSaved() here -- the screen stays put so the
                    // schedulingFailed message/retry action above is actually reachable, rather
                    // than navigating away and making it unreachable the instant it appears.
                    return@launch
                }
                // Best-effort, deliberately BEFORE onSaved() below -- without runWidgetUpdateSafely,
                // a widget/Glance/DataStore failure here would fall into the `catch (e: Exception)`
                // below, report saveFailed = true, and never call onSaved(), even though the
                // routine was already genuinely saved (see runWidgetUpdateSafely's own doc).
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
                onSaved()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: RoutineScheduleOverlapException) {
                _uiState.update { it.copy(isSaving = false, scheduleOverlap = true) }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to save routine", e)
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    /**
     * Retries ONLY [routineScheduler.scheduleActivation] for the already-saved [originalRoutine]
     * — never [routineRepository.save] again, and never generates a new
     * [java.util.UUID][CommuteRoutine.id] — see [save]'s own doc on why a scheduling failure
     * leaves [RoutineCreateUiState.schedulingFailed] set rather than [RoutineCreateUiState.saveFailed].
     * A no-op if [save] never actually succeeded yet (nothing to retry scheduling for) or a retry
     * is already in flight. On success, clears [RoutineCreateUiState.schedulingFailed] and calls
     * [onScheduled] — the same navigate-away effect [save]'s own [onSaved] would have had, had
     * scheduling succeeded the first time — so the create/edit flow still completes normally once
     * the routine is both saved AND correctly scheduled. On another failure, the message/retry
     * action simply remain visible for another attempt; [se.blick.app.scheduling.NotificationRecoveryCoordinator]'s
     * own `onAppStart` is still the eventual fallback if the user leaves without retrying again.
     */
    fun retryScheduling(onScheduled: () -> Unit) {
        val routine = originalRoutine ?: return
        if (_uiState.value.isRetryingScheduling) return
        _uiState.update { it.copy(isRetryingScheduling = true) }
        viewModelScope.launch {
            try {
                routineScheduler.scheduleActivation(routine)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isRetryingScheduling = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Retry scheduling failed for routine ${routine.id}", e)
                _uiState.update { it.copy(isRetryingScheduling = false) }
                return@launch
            }
            _uiState.update { it.copy(isRetryingScheduling = false, schedulingFailed = false) }
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            onScheduled()
        }
    }
}

/** Matches by the routine's real identity (line id + direction code + transport mode) —
 * never by destination text, which is display-only (see [DirectionOption]'s doc comment). */
private fun DirectionOption.matchesForPreselect(other: DirectionOption): Boolean =
    lineId == other.lineId && directionCode == other.directionCode && transportMode == other.transportMode
