package se.blick.app.ui.screens.routinedetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.GetRankedJourneysUseCase
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.GetDisruptionsUseCase
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.departureIdentity
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.notification.RoutineNotificationMapper
import se.blick.app.notification.RoutineNotifier
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.NextOccurrence
import se.blick.app.scheduling.NextOccurrenceCalculator
import se.blick.app.scheduling.NotificationRecoveryReporter
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.ui.navigation.Routes
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
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
    /** Derived from [CommuteRoutine.pausedDate] vs. "today" — see [RoutineDetailsViewModel.today]
     * for why this isn't just `LocalDate.now()` or `LocalDate.now(clock)`. */
    val isPausedToday: Boolean = false,
    /**
     * The live-departures engine's own state. On a manual refresh this is deliberately
     * NOT reset back to [LiveDeparturesState.Loading] — see [isRefreshingDepartures] — so
     * the previously loaded departures (or empty/error message) stay on screen instead of
     * the whole section blanking out while refreshing.
     */
    val departures: LiveDeparturesState = LiveDeparturesState.Loading,
    val isRefreshingDepartures: Boolean = false,
    /** The dedicated disruptions section's own state — loaded independently of, and never
     * affected by the failure of, [departures] (see [RoutineDetailsViewModel.loadDisruptions]'s
     * own doc). Like [departures], an automatic/manual refresh tick does not reset this back to
     * [DisruptionsState.Loading]; only the very first load (or one following an edit that
     * changed the routine's site/line/direction/mode) does. */
    val disruptions: DisruptionsState = DisruptionsState.Loading,
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
    /** True once a Room mutation ([RoutineDetailsViewModel.toggleEnabled]/
     * [RoutineDetailsViewModel.pauseToday]/[RoutineDetailsViewModel.resumeToday]/
     * [RoutineDetailsViewModel.reload]) has genuinely succeeded but the immediately-following
     * [RoutineScheduler] call failed — see [RoutineDetailsViewModel.retryScheduling]'s own doc.
     * Deliberately NOT the same signal as [enabledActionFailed]/[pauseActionFailed]: those only
     * ever fire when the ROOM write itself failed, never for a downstream scheduling failure on
     * top of an already-successful one. */
    val schedulingFailed: Boolean = false,
    /** Guards [RoutineDetailsViewModel.retryScheduling] against an overlapping second tap. */
    val isRetryingScheduling: Boolean = false,
    /** Mirrors `AppSettingsDataStore.hasSeenNotificationRationale` — see
     * [se.blick.app.ui.screens.routinecreate.RoutineCreateUiState]'s identical field for why
     * this exists. */
    val hasSeenNotificationRationale: Boolean = false,
    /** The one shared [NotificationAvailability] read (see [NotificationAvailabilityChecker])
     * — recomputed every time [runAutoRefresh] (re)starts, i.e. every time this screen becomes
     * active, so returning from system Settings, a permission-result callback, or any other
     * change never leaves this stale for the screen's whole lifetime (unlike a plain
     * `remember(context)` snapshot taken once). Defaults to [NotificationAvailability.Available]
     * purely as a harmless initial value until the first real check runs in `init`. */
    val notificationAvailability: NotificationAvailability = NotificationAvailability.Available,
    val journeys: List<JourneyPlan> = emptyList(),
    val journeysUnavailable: Boolean = false,
    /**
     * When [journeys] was last successfully evaluated — captured from [Clock.instant] AFTER
     * [GetRankedJourneysUseCase] returns (see [RoutineDetailsViewModel.loadJourneys]), never
     * before it's called. Exists purely so a completed automatic refresh is always observable to
     * [kotlinx.coroutines.flow.StateFlow], even when the newly-fetched [journeys] are structurally
     * equal to the previous list: [StateFlow] suppresses an update whose new value is `equals` to
     * the old one, and a `data class`'s `equals` compares every field — without this field
     * changing too, an identical-looking refresh would never re-emit, leaving Compose showing a
     * stale countdown (or briefly retaining an already-departed journey) for minutes at a time
     * even though the automatic ~30-second fetch loop was working correctly the whole time. Never
     * used to change which journeys are shown or how they're ranked — only to make an otherwise-
     * unchanged refresh visible. Defaults to [Instant.EPOCH], a harmless placeholder before the
     * very first journeys fetch has ever completed (this screen shows no journey cards yet at
     * that point regardless of what `now` filtering would do with it).
     */
    val journeysEvaluatedAt: Instant = Instant.EPOCH,
    val isUpdatingJourneyTransportModes: Boolean = false,
    val journeyTransportModesUpdateFailed: Boolean = false,
)

/**
 * Loads one saved [CommuteRoutine] by the id supplied via navigation, then fetches its next
 * two relevant departures through the existing [GetLiveDeparturesUseCase] — no departure
 * filtering, sorting, countdown, or failure-classification logic is reimplemented here.
 *
 * This is a foreground, manually-refreshable preview only: there is no periodic background
 * refresh loop and no automatic notification updates (see [showDebugTestNotification]'s doc
 * for the debug-only manual exception). The last successful [LiveDeparturesSnapshot] used for
 * [LiveDeparturesState.Stale] fallback is durably persisted via [StaleSnapshotRepository]
 * (Room-backed — see that interface's own doc), not just held in memory, so it survives this
 * ViewModel — and the whole process — being killed and recreated; it is also shared with
 * [se.blick.app.scheduling.RoutineActiveWindowWorker], so either one's successful fetch can
 * serve as the other's stale fallback.
 */
@HiltViewModel
class RoutineDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val getLiveDepartures: GetLiveDeparturesUseCase,
    private val getDisruptions: GetDisruptionsUseCase,
    private val staleSnapshotRepository: StaleSnapshotRepository,
    private val routineNotifier: RoutineNotifier,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val promotedNotificationChecker: PromotedNotificationChecker,
    private val notificationRecoveryReporter: NotificationRecoveryReporter,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
    private val getRankedJourneys: GetRankedJourneysUseCase? = null,
) : ViewModel() {

    private val routineId: String =
        checkNotNull(savedStateHandle.get<String>(Routes.RoutineDetails.ARG_ROUTINE_ID)) {
            "RoutineDetailsViewModel requires a '${Routes.RoutineDetails.ARG_ROUTINE_ID}' navigation argument"
        }

    private val _uiState = MutableStateFlow(RoutineDetailsUiState())
    val uiState: StateFlow<RoutineDetailsUiState> = _uiState.asStateFlow()

    /** Guards against an overlapping/superseded departures fetch overwriting a newer one —
     * same stored-Job + request-generation-token pattern as
     * [se.blick.app.ui.screens.routinecreate.RoutineCreateViewModel]'s direction-race fix. */
    private var departuresJob: Job? = null
    private var departuresRequestId = 0

    /** Guards against an overlapping/superseded disruptions fetch overwriting a newer one —
     * same job + request-generation-token pattern as [departuresJob]/[departuresRequestId],
     * but kept entirely separate: cancelling or superseding one must never affect the other,
     * since a disruptions failure must never delay or replace a departures update (or vice
     * versa) — see [loadDisruptions]'s own doc. */
    private var disruptionsJob: Job? = null
    private var disruptionsRequestId = 0

    /** True once the very first departures fetch (from [init], shown as
     * [LiveDeparturesState.Loading]) has actually been triggered — [runAutoRefresh] uses this
     * to tell "this is the very first time the screen has ever become active" (nothing extra
     * to do; [init]'s own fetch already covers it) apart from "the screen was stopped and has
     * just become active again" (needs its own immediate silent re-fetch). */
    private var hasStartedAutoRefreshOnce = false

    /** Non-null only while [runAutoRefresh] has an active loop running — guards against a
     * second concurrent loop starting (see [runAutoRefresh]'s own doc). */
    private var autoRefreshJob: Job? = null

    /** "Today" for pause/resume purposes, resolved from [clock]'s instant combined with
     * [deviceZoneProvider]'s CURRENT zone — mirroring
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own `zonedNow()` and
     * [se.blick.app.notification.StopRoutineNotificationAction]'s identical computation.
     * Deliberately never a zone-less `LocalDate.now(clock)`: [clock] is
     * `Clock.systemUTC()` in production (see `di/TimeModule.kt`), so resolving "today"
     * against it directly would compute the wrong calendar date shortly after local
     * midnight in any zone ahead of UTC (e.g. Sweden) — the worker's own mid-loop
     * `pausedDate == today` check (which this screen's pause/resume actions must agree
     * with) already uses the device zone, so this must too. */
    private fun zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())

    private fun today(): LocalDate = zonedNow().toLocalDate()

    /**
     * [GetRankedJourneysUseCase]'s own `searchUntil` — see that parameter's doc. Only ever
     * non-null for [NextOccurrence.ActiveNow], derived from the same shared
     * [NextOccurrenceCalculator] scheduling logic [se.blick.app.scheduling.RoutineActiveWindowWorker]
     * and every other occurrence calculation in this app already uses, rather than a duplicated
     * one — its `windowEnd` is a genuine bound on the search the backend is about to run RIGHT
     * NOW, since the backend always searches forward from the request's own current time.
     *
     * [NextOccurrence.Upcoming] deliberately returns `null`, NOT `occurrence.windowEnd.toInstant()`:
     * that end belongs to a FUTURE occurrence's window, not to a search starting now — combining
     * today's "now" with a future occurrence's own end would silently search across everything
     * in between (e.g. overnight), never a boundary the backend can meaningfully honor for a
     * search it runs at the current instant. If forward-looking journey planning for an upcoming
     * occurrence is ever wanted, it needs its own proper `searchFrom = upcoming.windowStart` /
     * `searchUntil = upcoming.windowEnd` contract threaded through as a pair — not implemented
     * here. [NextOccurrence.None] also returns `null`: no eligible occurrence at all right now or
     * ahead (`activeDays` empty, or every candidate day excluded). [GetRankedJourneysUseCase]
     * already treats a null `searchUntil` as "no boundary to offer", never inventing one here
     * either.
     */
    private fun currentSearchUntil(routine: CommuteRoutine): Instant? =
        when (val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, zonedNow(), excludedDate = routine.pausedDate)) {
            is NextOccurrence.ActiveNow -> occurrence.windowEnd.toInstant()
            is NextOccurrence.Upcoming -> null
            NextOccurrence.None -> null
        }

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
                        isPausedToday = routine.pausedDate == today(),
                    )
                }
                loadDepartures(routine, RefreshTrigger.INITIAL)
                if (routine.type == RoutineType.LINE_DIRECTION) loadDisruptions(routine, isInitial = true)
            }
        }
        viewModelScope.launch {
            val seen = appSettingsDataStore.settings.first().hasSeenNotificationRationale
            _uiState.update { it.copy(hasSeenNotificationRationale = seen) }
        }
        refreshNotificationAvailability()
    }

    /** Records that the production notification-permission rationale (see
     * [se.blick.app.ui.notification.rememberNotificationPermissionGate]) has now been shown
     * once — see [se.blick.app.ui.screens.routinecreate.RoutineCreateViewModel]'s identical
     * method for the same reasoning. Also re-checks [notificationAvailability] immediately:
     * this fires right after the permission-request result (granted, denied, or the rationale
     * dialog dismissed without asking — see `rememberNotificationPermissionGate`'s `finishAndRun`),
     * one of the specific moments the status must never be left stale for (see
     * [refreshNotificationAvailability]'s own doc). */
    fun markNotificationRationaleSeen() {
        _uiState.update { it.copy(hasSeenNotificationRationale = true) }
        viewModelScope.launch { appSettingsDataStore.setHasSeenNotificationRationale(true) }
        refreshNotificationAvailability()
    }

    /**
     * Re-reads [NotificationAvailability] via the shared [notificationAvailabilityChecker]
     * (see that interface's own doc — the same source of truth [se.blick.app.notification.AndroidRoutineNotifier]
     * and `RoutineActiveWindowWorker` read) and stores it in [RoutineDetailsUiState]. A cheap,
     * synchronous, non-suspending read, so this is called liberally rather than cached
     * indefinitely: once in [init] (the screen's first-ever check), and again at the top of
     * every [runAutoRefresh] call — which, being driven by `repeatOnLifecycle(STARTED)` in
     * [RoutineDetailsScreen], fires both when the screen first becomes active AND every time it
     * becomes active again after being stopped (returning from system Settings, the app being
     * backgrounded and resumed, navigating away and back, or a rotation) — exactly the cases a
     * one-shot `remember(context)` snapshot in the UI layer would otherwise miss. Also called
     * from [markNotificationRationaleSeen] for the permission-result case.
     *
     * Deliberately does NOT itself call [RoutineScheduler.scheduleActivation] or reconcile the
     * widget — `NotificationRecoveryCoordinator` (see that class's own doc) is the sole
     * authority for notification-availability recovery now, exactly like it is for
     * `BlickApplication`'s cold-start and foreground triggers, so two independent code paths can
     * never race each other into scheduling the same routine's activation twice. This screen's
     * only responsibility here is REPORTING what it just observed: if [current] is not
     * [NotificationAvailability.Available], [NotificationRecoveryReporter.reportUnavailable]
     * durably records that a recovery attempt is owed — the coordinator's own foreground check
     * (`BlickApplication`'s `ProcessLifecycleOwner` `ON_START`) is what actually resumes the
     * routine's active window once notifications become available again, whether or not this
     * screen happens to still be open at that point.
     */
    fun refreshNotificationAvailability() {
        val current = notificationAvailabilityChecker.check()
        _uiState.update { it.copy(notificationAvailability = current) }
        if (current != NotificationAvailability.Available) {
            viewModelScope.launch { notificationRecoveryReporter.reportUnavailable() }
        }
    }

    /**
     * Re-reads just [CommuteRoutine.enabled]/[CommuteRoutine.pausedDate] from storage and
     * applies them to [RoutineDetailsUiState.routine]/[RoutineDetailsUiState.isPausedToday] —
     * called at the top of every [runAutoRefresh] (i.e. every time this screen becomes active),
     * mirroring [refreshNotificationAvailability]'s own "never let this go stale across a
     * reactivation" reasoning.
     *
     * Without this, an already-alive instance of this screen (backgrounded rather than
     * destroyed) never notices a pause/resume written by a path OUTSIDE it — most notably
     * [se.blick.app.notification.StopRoutineNotificationAction], which pauses the routine for
     * today directly via [RoutineRepository.pauseForDate] when the ongoing notification's Stop
     * action is tapped. [toggleEnabled]/[pauseToday]/[resumeToday] already keep this screen's
     * OWN writes in sync locally; this covers every other writer.
     *
     * Deliberately much narrower than [reload]: a mere reactivation is not an edit, so unlike
     * [reload] this never touches [RoutineDetailsUiState.departures]/[RoutineDetailsUiState.disruptions],
     * never clears [StaleSnapshotRepository], and never calls [RoutineScheduler] — there is no
     * new departure identity to reconcile and nothing here should trigger scheduling. A read
     * failure is silently logged and otherwise ignored, the same best-effort spirit as
     * [refreshNotificationAvailability]: whatever was already displayed stays displayed rather
     * than blanking the screen for a transient Room hiccup.
     */
    private suspend fun refreshRoutineState() {
        if (_uiState.value.routine == null) return
        try {
            val fresh = routineRepository.getById(routineId) ?: return
            _uiState.update { it.copy(routine = fresh, isPausedToday = fresh.pausedDate == today()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to refresh routine $routineId's own enabled/paused state on screen reactivation", e)
        }
    }

    /**
     * The lifecycle-aware 30-second auto-refresh loop (see the product doc's "Automatic
     * refresh on Routine Details" requirement). Intended to be driven from a
     * `repeatOnLifecycle(STARTED)` block in [RoutineDetailsScreen] — cancelling that block
     * (screen stopped/backgrounded/left) is what stops this loop; there is no separate
     * `stopAutoRefresh()` because structured concurrency already covers it.
     *
     * The very first time this is ever called (right as the screen first becomes STARTED),
     * [init]'s own fetch already just ran (or is running) as the screen's "fetch immediately"
     * moment, so this does not fetch again immediately — it only waits
     * [AUTO_REFRESH_INTERVAL_MS] and begins its recurring, silent [RefreshTrigger.AUTOMATIC]
     * ticks from there. Every call after the first (the screen having been stopped and become
     * active again — background/foreground, navigating away and back, or a rotation) DOES
     * fetch immediately before resuming the 30-second ticks, matching "restart with an
     * immediate fetch when the screen becomes active again" — that fetch is also
     * [RefreshTrigger.AUTOMATIC] (silent), since the previous state is already on screen and
     * should not be blanked to a loading spinner just because the screen was reactivated.
     *
     * Idempotent against being invoked twice concurrently (e.g. a rapid double recomposition
     * re-entering `repeatOnLifecycle` before the first call's `finally` has cleared
     * [autoRefreshJob]) — a second call while one is already active is a no-op rather than a
     * second concurrent loop, on top of `repeatOnLifecycle`'s own guarantee that only one of
     * its blocks ever runs at a time.
     *
     * Also re-checks [notificationAvailability][refreshNotificationAvailability] and the
     * routine's own [enabled][refreshRoutineState]/paused state on every call, not only
     * departures/disruptions — see those two methods' own docs for why each needs a
     * reactivation hook of its own.
     */
    suspend fun runAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        // Every call = every time this screen becomes active (see this function's own doc) --
        // re-check notification availability right away so it can never go stale for the
        // screen's whole lifetime (see refreshNotificationAvailability's doc).
        refreshNotificationAvailability()
        // Same reasoning, for the routine's own enabled/pausedDate -- see refreshRoutineState's
        // own doc for why this can otherwise go stale on an already-alive instance of this
        // screen.
        refreshRoutineState()
        coroutineScope {
            val job = launch {
                uiState.map { it.routine }.filterNotNull().first()
                if (hasStartedAutoRefreshOnce) {
                    _uiState.value.routine?.let {
                        loadDepartures(it, RefreshTrigger.AUTOMATIC)
                        if (it.type == RoutineType.LINE_DIRECTION) loadDisruptions(it, isInitial = false)
                    }
                }
                hasStartedAutoRefreshOnce = true
                while (isActive) {
                    delay(AUTO_REFRESH_INTERVAL_MS)
                    _uiState.value.routine?.let {
                        loadDepartures(it, RefreshTrigger.AUTOMATIC)
                        if (it.type == RoutineType.LINE_DIRECTION) loadDisruptions(it, isInitial = false)
                    }
                }
            }
            autoRefreshJob = job
            try {
                job.join()
            } finally {
                autoRefreshJob = null
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
        if (!pausedDate.isBefore(today())) return routine
        routineRepository.clearPause(routine.id)
        return routine.copy(pausedDate = null)
    }

    /** Manual refresh action for the "Next departures" section — kept as an optional
     * fallback alongside [runAutoRefresh]'s automatic 30-second loop. A no-op if the routine
     * itself never loaded (nothing to refresh departures for). */
    fun refresh() {
        val routine = _uiState.value.routine ?: return
        loadDepartures(routine, RefreshTrigger.MANUAL)
        if (routine.type == RoutineType.LINE_DIRECTION) loadDisruptions(routine, isInitial = false)
    }

    /** Persists the exact-destination routine's Journey Planner mode allow-list, then refreshes
     * every consumer from the same stored routine. Walking transfer legs remain implicitly
     * allowed and an empty public-mode selection is rejected by the UI and here. */
    fun updateJourneyTransportModes(modes: Set<TransportMode>) {
        val state = _uiState.value
        val routine = state.routine ?: return
        val supportedModes = modes.filterTo(linkedSetOf(), JOURNEY_TRANSPORT_MODE_OPTIONS::contains)
        if (routine.type != RoutineType.EXACT_DESTINATION || supportedModes.isEmpty() ||
            state.isUpdatingJourneyTransportModes || supportedModes == routine.allowedJourneyTransportModes
        ) return

        val updated = routine.copy(allowedJourneyTransportModes = supportedModes)
        _uiState.update {
            it.copy(isUpdatingJourneyTransportModes = true, journeyTransportModesUpdateFailed = false)
        }
        viewModelScope.launch {
            try {
                routineRepository.save(updated)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isUpdatingJourneyTransportModes = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to update journey transport modes for ${routine.id}", e)
                _uiState.update {
                    it.copy(isUpdatingJourneyTransportModes = false, journeyTransportModesUpdateFailed = true)
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    routine = updated,
                    isUpdatingJourneyTransportModes = false,
                    journeyTransportModesUpdateFailed = false,
                    schedulingFailed = false,
                )
            }
            loadDepartures(updated, RefreshTrigger.MANUAL)
            try {
                routineScheduler.scheduleActivation(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Journey modes were saved but rescheduling ${routine.id} failed", e)
                _uiState.update { it.copy(schedulingFailed = true) }
            }
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
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
     *
     * The whole body is wrapped in a try/catch (a genuine [CancellationException] always
     * rethrown unconverted) so an ordinary Room/cache failure — [routineRepository.getById]
     * itself, or [clearExpiredPauseIfNeeded]'s own write — can never escape uncaught into
     * `viewModelScope`, which has no default exception handler on Android: before this, such a
     * failure would have crashed the whole screen instead of merely leaving it showing
     * whatever was already correctly displayed. Nothing already applied to [_uiState] is ever
     * rolled back on a later failure — the freshly-read [fresh] routine is real, Room-confirmed
     * data the moment it's applied, so a failure in the scheduling call further below must not
     * un-display it either (see that call's own, separate try/catch — the same persistence-vs-
     * scheduling split [toggleEnabled]/[pauseToday]/[deleteRoutine] all use).
     */
    fun reload() {
        if (_uiState.value.isRoutineLoading) return
        viewModelScope.launch {
            try {
                val loaded = routineRepository.getById(routineId) ?: return@launch
                val fresh = clearExpiredPauseIfNeeded(loaded)
                val previous = _uiState.value.routine
                val departureConfigChanged = previous == null || previous.departureIdentity() != fresh.departureIdentity()
                _uiState.update {
                    it.copy(
                        routine = fresh,
                        isPausedToday = fresh.pausedDate == today(),
                        schedulingFailed = false,
                    )
                }
                if (departureConfigChanged) {
                    // The persisted snapshot (if any) belongs to the OLD identity and must never
                    // be offered as a stale fallback for the new one — loadDepartures's own
                    // identity check already prevents this (see StaleSnapshotRepository.get's own
                    // doc), but clearing it here too makes the invariant explicit rather than
                    // relying on that check alone, and prevents it from lingering for no purpose.
                    staleSnapshotRepository.clear(routineId)
                    loadDepartures(fresh, RefreshTrigger.INITIAL)
                    loadDisruptions(fresh, isInitial = true)
                }
                try {
                    // The edit may also have changed enabled/schedule/pause fields, which
                    // affects when this routine's active window should next run -- always
                    // recompute, not only when the departure-relevant identity changed. Its own
                    // try/catch, separate from the Room read/write above: a scheduling failure
                    // here is a secondary side effect of an already-successful reload, not a
                    // reason to let the exception propagate and lose the state already applied
                    // above -- NotificationRecoveryCoordinator's own onAppStart unconditionally
                    // reconciles every enabled routine's scheduling on the next app start
                    // regardless.
                    routineScheduler.scheduleActivation(fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Routine $routineId reloaded but rescheduling its activation failed", e)
                    _uiState.update { it.copy(schedulingFailed = true) }
                }
                // Best-effort -- without runWidgetUpdateSafely, a widget/Glance/DataStore
                // failure here would otherwise be caught by the outer catch below and merely
                // logged, which is harmless either way, but this keeps the widget attempt
                // independent of whatever the scheduling call above did (see runWidgetUpdateSafely's
                // own doc).
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to reload routine $routineId; keeping the previously displayed state", e)
            }
        }
    }

    /**
     * Toggles [CommuteRoutine.enabled] only — never touches `pausedDate` or any other field
     * (see [RoutineRepository.setEnabled]'s doc). Guarded against overlapping taps; on a Room
     * write failure the local [RoutineDetailsUiState.routine] is left exactly as it was (the
     * write never happened), paired with a friendly, retryable
     * [RoutineDetailsUiState.enabledActionFailed].
     *
     * **Persistence succeeding and scheduling succeeding are two different results.** Once
     * [RoutineRepository.setEnabled] accepts the write, [newEnabled] IS the persisted truth —
     * [RoutineDetailsUiState.routine] is updated to reflect it immediately, before
     * [routineScheduler] is even called, so a SUBSEQUENT [RoutineScheduler.scheduleActivation]/
     * [RoutineScheduler.cancelActivation] failure (its own, separate try/catch below) can never
     * leave the UI showing the pre-toggle state, or report [RoutineDetailsUiState.enabledActionFailed]
     * for a write that actually succeeded. */
    fun toggleEnabled() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingEnabled) return
        val newEnabled = !routine.enabled
        _uiState.update { it.copy(isTogglingEnabled = true, enabledActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.setEnabled(routine.id, newEnabled)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingEnabled = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to toggle enabled for routine ${routine.id}", e)
                _uiState.update { it.copy(isTogglingEnabled = false, enabledActionFailed = true) }
                return@launch
            }
            val updated = routine.copy(enabled = newEnabled)
            _uiState.update {
                it.copy(isTogglingEnabled = false, routine = it.routine?.copy(enabled = newEnabled), schedulingFailed = false)
            }
            try {
                if (newEnabled) {
                    routineScheduler.scheduleActivation(updated)
                } else {
                    routineScheduler.cancelActivation(updated.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Routine ${routine.id} enabled=$newEnabled was saved but (re)scheduling it failed", e)
                _uiState.update { it.copy(schedulingFailed = true) }
            }
            // Best-effort -- see runWidgetUpdateSafely's own doc.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
    }

    /** Sets `pausedDate` to today (see [RoutineRepository.pauseForDate]) — independent of
     * [CommuteRoutine.enabled], never toggles it. Guarded against overlapping taps with
     * [resumeToday] (they're mutually exclusive actions on the same field). Persistence and
     * scheduling are two different results — see [toggleEnabled]'s identical reasoning: once
     * the Room write succeeds, [RoutineDetailsUiState.isPausedToday]/`routine` reflect it
     * immediately, before [routineScheduler] is even called, so a subsequent scheduling
     * failure (its own try/catch) can never revert the UI or report
     * [RoutineDetailsUiState.pauseActionFailed] for a write that actually succeeded. */
    fun pauseToday() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingPause) return
        val today = today()
        _uiState.update { it.copy(isTogglingPause = true, pauseActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.pauseForDate(routine.id, today)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingPause = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to pause routine ${routine.id} for today", e)
                _uiState.update { it.copy(isTogglingPause = false, pauseActionFailed = true) }
                return@launch
            }
            _uiState.update {
                it.copy(isTogglingPause = false, isPausedToday = true, routine = it.routine?.copy(pausedDate = today), schedulingFailed = false)
            }
            try {
                // Recompute the next eligible activation excluding today -- see
                // NextOccurrenceCalculator's excludedDate parameter.
                routineScheduler.scheduleActivation(routine.copy(pausedDate = today))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Routine ${routine.id} was paused for today but rescheduling it failed", e)
                _uiState.update { it.copy(schedulingFailed = true) }
            }
            // Best-effort -- see runWidgetUpdateSafely's own doc.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
    }

    /** Clears `pausedDate` (see [RoutineRepository.clearPause]) — independent of
     * [CommuteRoutine.enabled], never toggles it. Persistence and scheduling are two different
     * results — see [toggleEnabled]'s identical reasoning. */
    fun resumeToday() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isTogglingPause) return
        _uiState.update { it.copy(isTogglingPause = true, pauseActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.clearPause(routine.id)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isTogglingPause = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to resume routine ${routine.id}", e)
                _uiState.update { it.copy(isTogglingPause = false, pauseActionFailed = true) }
                return@launch
            }
            _uiState.update {
                it.copy(isTogglingPause = false, isPausedToday = false, routine = it.routine?.copy(pausedDate = null), schedulingFailed = false)
            }
            try {
                routineScheduler.scheduleActivation(routine.copy(pausedDate = null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Routine ${routine.id} was resumed but rescheduling it failed", e)
                _uiState.update { it.copy(schedulingFailed = true) }
            }
            // Best-effort -- see runWidgetUpdateSafely's own doc.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        }
    }

    /**
     * Retries ONLY the scheduling side for the CURRENT persisted [RoutineDetailsUiState.routine]
     * — never repeats [toggleEnabled]/[pauseToday]/[resumeToday]'s own Room write, which has
     * already genuinely succeeded by the time [RoutineDetailsUiState.schedulingFailed] is ever
     * set (see those methods' own docs). Deliberately calls
     * [RoutineScheduler.scheduleActivation] unconditionally, never [RoutineScheduler.cancelActivation]
     * directly: [WorkManagerRoutineScheduler][se.blick.app.scheduling.WorkManagerRoutineScheduler]'s
     * own `scheduleActivation` already cancels any scheduled work for a disabled routine
     * internally, so this one call is correct for every persisted state
     * ([CommuteRoutine.enabled] true or false, paused or not) without this method needing to
     * duplicate that branching itself. A no-op if the routine hasn't loaded or a retry is
     * already in flight. */
    fun retryScheduling() {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isRetryingScheduling) return
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
        }
    }

    /**
     * Deletes the routine. The confirmation dialog itself is UI-local state (see
     * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]) — this is only called
     * once the user has actually confirmed. Guarded against a repeated confirm tap firing a
     * second delete while the first is still in flight. [onDeleted] is only invoked after
     * [RoutineRepository.delete] actually succeeds, so the caller can navigate back to the
     * list; a Room failure THERE keeps the screen on the routine with a friendly, retryable
     * [RoutineDetailsUiState.deleteFailed].
     *
     * **Persistence succeeding and cleanup succeeding are two different results.** Once Room
     * deletion succeeds, the routine IS deleted — a SUBSEQUENT [RoutineScheduler.cancelActivation]
     * failure (its own, separate try/catch below) must never be reported as
     * [RoutineDetailsUiState.deleteFailed], and [onDeleted] still runs so the caller navigates
     * away as normal: re-deleting (or re-inserting) the Room row is never attempted merely
     * because a cleanup step failed. [routineNotifier.remove] is unconditional and never
     * throws (see that interface's own doc), so it needs no try/catch of its own.
     *
     * Deliberately does NOT set [RoutineDetailsUiState.schedulingFailed]: unlike
     * [toggleEnabled]/[pauseToday]/[resumeToday]/[reload], there is no future occurrence left to
     * retry scheduling FOR once the routine itself is gone — [RoutineScheduler.cancelActivation]
     * is a best-effort cleanup of now-stale work, not something a user action can meaningfully
     * retry. Existing safety nets already cover a failed cancellation: any stale WorkManager
     * request left behind fires at most once, and [se.blick.app.scheduling.RoutineActiveWindowWorker]'s
     * own `doWork` already re-reads the routine first and safely no-ops (never reschedules)
     * once it finds nothing there.
     */
    fun deleteRoutine(onDeleted: () -> Unit) {
        val state = _uiState.value
        val routine = state.routine ?: return
        if (state.isDeleting) return
        _uiState.update { it.copy(isDeleting = true, deleteFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.delete(routine.id)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isDeleting = false) }
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to delete routine ${routine.id}", e)
                _uiState.update { it.copy(isDeleting = false, deleteFailed = true) }
                return@launch
            }
            _uiState.update { it.copy(isDeleting = false) }
            try {
                routineScheduler.cancelActivation(routine.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Routine ${routine.id} was deleted but cancelling its scheduled work failed", e)
            }
            routineNotifier.remove()
            // Best-effort -- see runWidgetUpdateSafely's own doc.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            onDeleted()
        }
    }

    /**
     * Debug-only manual trigger (see `BuildConfig.DEBUG`-gated UI in
     * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]) for verifying
     * [RoutineNotifier] end-to-end before any scheduler exists to call it automatically.
     * Reuses the already-loaded [RoutineDetailsUiState.routine] and
     * [RoutineDetailsUiState.departures] as-is — no new fetch is triggered, and the existing
     * departure-filtering/countdown logic in [se.blick.app.domain.usecase.LiveDeparturesProcessor]
     * is not reimplemented; only [RoutineNotificationMapper] converts the already-prepared
     * state to a [se.blick.app.notification.RoutineNotificationModel]. Notification-permission
     * handling (including any runtime `POST_NOTIFICATIONS` request on API 33+) is the caller's
     * responsibility — this method never touches `AppSettingsDataStore`/`hasSeenNotificationRationale`.
     *
     * Returns null (a no-op) if the routine hasn't loaded yet — there's nothing to post for.
     * Otherwise returns [routineNotifier]'s own real [NotificationPostResult] unchanged, so
     * the debug UI can tell an actual [NotificationPostResult.Posted] apart from
     * [NotificationPostResult.NotificationsDisabled]/[NotificationPostResult.Failed] rather
     * than assuming success merely because this function was called.
     */
    fun showDebugTestNotification(): NotificationPostResult? {
        val state = _uiState.value
        val routine = state.routine ?: return null
        val topDisruption = (state.disruptions as? DisruptionsState.Loaded)?.disruptions?.firstOrNull()
        val model = RoutineNotificationMapper.map(routine, state.departures, clock.instant(), topDisruption)
        return routineNotifier.showOrUpdate(model)
    }

    /** Debug-only counterpart to [showDebugTestNotification], so [RoutineNotifier.remove]
     * can also be manually verified. */
    fun removeDebugTestNotification() {
        routineNotifier.remove()
    }

    /** Whether the system currently reports Blick's ongoing notification is eligible for
     * promotion to the lock-screen Live Update surface (see [PromotedNotificationChecker]'s own
     * doc on why this is separate from [NotificationAvailability], and on why "eligible" is not
     * the same as "will actually render"). Used by both the debug notification section
     * (alongside [showDebugTestNotification]'s own result, as a platform-eligibility check
     * only, not a substitute for real device verification) and, in production, to decide
     * whether to offer a link to Android's own Live Update settings screen. */
    fun isLiveUpdatePromotable(): Boolean = promotedNotificationChecker.isPromotable()

    private fun loadDepartures(routine: CommuteRoutine, trigger: RefreshTrigger) {
        if (routine.type == RoutineType.EXACT_DESTINATION) {
            loadJourneys(routine, trigger)
            return
        }
        departuresJob?.cancel()
        val requestId = ++departuresRequestId
        val identity = routine.departureIdentity()

        _uiState.update {
            when (trigger) {
                // The very first fetch (or one following an edit that changed the departure
                // identity, see reload()) is the only case that should blank the section to
                // a loading spinner.
                RefreshTrigger.INITIAL -> it.copy(departures = LiveDeparturesState.Loading)
                RefreshTrigger.MANUAL -> it.copy(isRefreshingDepartures = true)
                // A silent, periodic 30-second tick (or an immediate fetch on reactivation)
                // must never blank already-visible departures/state to a loading spinner —
                // the UI only updates once new data (or a new error/stale state) actually
                // arrives below.
                RefreshTrigger.AUTOMATIC -> it
            }
        }

        departuresJob = viewModelScope.launch {
            // The persisted snapshot is only a valid stale fallback for a fetch of the EXACT
            // same departure identity that produced it (see StaleSnapshotRepository.get's own
            // doc) — a manual or automatic refresh of the same routine reuses it, but a fetch
            // following an edit to a different site/line/direction/mode must not.
            val previousForThisFetch = staleSnapshotRepository.get(routineId, identity)

            getLiveDepartures(routine, previous = previousForThisFetch).collect { state ->
                if (requestId != departuresRequestId) return@collect

                if (state is LiveDeparturesState.Loading) {
                    // Only the very first load surfaces Loading to the UI — a manual refresh
                    // already set isRefreshingDepartures above (and keeps the previous
                    // departures/state visible underneath it), and an automatic tick shows
                    // nothing at all until its terminal state arrives.
                    if (trigger == RefreshTrigger.INITIAL) {
                        _uiState.update { it.copy(departures = state) }
                    }
                    return@collect
                }

                if (state is LiveDeparturesState.Live) {
                    staleSnapshotRepository.save(routineId, identity, state.snapshot)
                }
                _uiState.update { it.copy(departures = state, isRefreshingDepartures = false) }
            }
        }
    }

    private fun loadJourneys(routine: CommuteRoutine, trigger: RefreshTrigger) {
        departuresJob?.cancel()
        val useCase = getRankedJourneys
        val originId = routine.journeyOriginId
        val destinationId = routine.journeyDestinationId
        if (useCase == null || originId == null || destinationId == null) {
            _uiState.update { it.copy(journeysUnavailable = true, isRefreshingDepartures = false) }
            return
        }
        if (trigger == RefreshTrigger.MANUAL) _uiState.update { it.copy(isRefreshingDepartures = true) }
        val searchUntil = currentSearchUntil(routine)
        departuresJob = viewModelScope.launch {
            try {
                val journeys = useCase(originId, destinationId, routine.allowedJourneyTransportModes, searchUntil)
                // Captured AFTER the use case returns, never before -- see
                // RoutineDetailsUiState.journeysEvaluatedAt's own doc. Stored in the SAME update as
                // `journeys` so both land in a single StateFlow emission: this timestamp changing is
                // what makes this refresh observable even when `journeys` itself is structurally
                // identical to what was already displayed.
                val evaluatedAt = clock.instant()
                _uiState.update {
                    it.copy(journeys = journeys, journeysUnavailable = false, isRefreshingDepartures = false, journeysEvaluatedAt = evaluatedAt)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _uiState.update { it.copy(journeysUnavailable = true, isRefreshingDepartures = false) }
            }
        }
    }

    /**
     * Loads the dedicated disruptions section's state via [getDisruptions] — entirely
     * independent of [loadDepartures]: its own job/request-id pair (see [disruptionsJob]'s own
     * doc), and a failure here (surfaced as [DisruptionsState.Unavailable] by
     * [GetDisruptionsUseCase] itself) never touches [RoutineDetailsUiState.departures] or
     * cancels an in-flight departures fetch, and vice versa.
     *
     * [isInitial] mirrors [RefreshTrigger.INITIAL] vs. [RefreshTrigger.AUTOMATIC]/
     * [RefreshTrigger.MANUAL] for departures: only the very first load for a given routine
     * identity (or one following an edit that changed site/line/direction/mode, see [reload])
     * blanks the section to [DisruptionsState.Loading]; every later automatic tick or manual
     * refresh keeps whatever is already on screen until the new terminal state arrives, so the
     * section never flashes back to a loading spinner every ~30 seconds.
     */
    private fun loadDisruptions(routine: CommuteRoutine, isInitial: Boolean) {
        disruptionsJob?.cancel()
        val requestId = ++disruptionsRequestId
        disruptionsJob = viewModelScope.launch {
            getDisruptions(routine).collect { state ->
                if (requestId != disruptionsRequestId) return@collect
                if (state is DisruptionsState.Loading && !isInitial) return@collect
                _uiState.update { it.copy(disruptions = state) }
            }
        }
    }

    companion object {
        /** The "roughly every 30 seconds" interval from the product doc's automatic-refresh
         * requirement — a plain `const val`, not a literal `delay(30_000)` inline, purely so
         * `RoutineDetailsViewModelTest`'s deterministic tests reference the exact same value
         * the production loop uses rather than a duplicated magic number. Advanced via
         * `StandardTestDispatcher`'s virtual time in tests (see this codebase's existing
         * `Dispatchers.setMain(StandardTestDispatcher())` convention) rather than a real wait —
         * `delay()` already respects that virtual clock with no separate Ticker abstraction
         * needed.
         */
        const val AUTO_REFRESH_INTERVAL_MS = 30_000L
    }
}

/** Distinguishes why a departures fetch was triggered — see [RoutineDetailsViewModel.loadDepartures]. */
private enum class RefreshTrigger { INITIAL, MANUAL, AUTOMATIC }
