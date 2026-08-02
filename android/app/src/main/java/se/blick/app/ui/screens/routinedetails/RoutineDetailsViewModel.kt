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
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.ui.navigation.Routes
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
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
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
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
    private fun today(): LocalDate = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone()).toLocalDate()

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
                loadDisruptions(routine, isInitial = true)
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
     * Also detects the specific TRANSITION from unavailable to available and, when it happens,
     * calls [onNotificationsBecameAvailable] — see that function's own doc for why this is
     * needed: without it, an enabled routine whose active window
     * [se.blick.app.scheduling.RoutineActiveWindowWorker] already cut short (via its own
     * `rescheduleSkippingToday`, once it noticed notifications were unavailable) would stay
     * silent until the routine's next eligible occurrence — tomorrow at the earliest — even if
     * the user re-enables notifications while today's window is still genuinely open.
     */
    fun refreshNotificationAvailability() {
        val previous = _uiState.value.notificationAvailability
        val current = notificationAvailabilityChecker.check()
        _uiState.update { it.copy(notificationAvailability = current) }
        if (previous != NotificationAvailability.Available && current == NotificationAvailability.Available) {
            onNotificationsBecameAvailable()
        }
    }

    /**
     * Fires the moment [RoutineDetailsUiState.notificationAvailability] transitions from
     * anything other than [NotificationAvailability.Available] TO
     * [NotificationAvailability.Available] — e.g. the user grants the runtime permission,
     * re-enables the app-wide notifications toggle, or re-enables the Blick channel
     * specifically, all from outside this screen (system Settings). A no-op if no routine has
     * loaded yet, or the loaded routine is disabled — there is nothing to (re)schedule for
     * either case.
     *
     * Calling [RoutineScheduler.scheduleActivation] again re-derives the correct occurrence
     * from scratch exactly like every other lifecycle call site in this class
     * ([toggleEnabled]/[pauseToday]/[resumeToday]/[reload]) already does: if the routine's
     * active window is still genuinely open right now, `WorkManagerRoutineScheduler` enqueues
     * it with a ZERO initial delay, so [se.blick.app.scheduling.RoutineActiveWindowWorker]
     * resumes within moments instead of waiting for the routine's next eligible occurrence; if
     * the window has since closed, this simply reconfirms the already-correct next occurrence,
     * a harmless no-op. [RoutineWidgetUpdater.reconcile] is best-effort, same reasoning as
     * every other widget call site in this class (see [runWidgetUpdateSafely]'s own doc).
     */
    private fun onNotificationsBecameAvailable() {
        val routine = _uiState.value.routine?.takeIf { it.enabled } ?: return
        routineScheduler.scheduleActivation(routine)
        viewModelScope.launch {
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
     */
    suspend fun runAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        // Every call = every time this screen becomes active (see this function's own doc) --
        // re-check notification availability right away so it can never go stale for the
        // screen's whole lifetime (see refreshNotificationAvailability's doc).
        refreshNotificationAvailability()
        coroutineScope {
            val job = launch {
                uiState.map { it.routine }.filterNotNull().first()
                if (hasStartedAutoRefreshOnce) {
                    _uiState.value.routine?.let {
                        loadDepartures(it, RefreshTrigger.AUTOMATIC)
                        loadDisruptions(it, isInitial = false)
                    }
                }
                hasStartedAutoRefreshOnce = true
                while (isActive) {
                    delay(AUTO_REFRESH_INTERVAL_MS)
                    _uiState.value.routine?.let {
                        loadDepartures(it, RefreshTrigger.AUTOMATIC)
                        loadDisruptions(it, isInitial = false)
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
        loadDisruptions(routine, isInitial = false)
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
            val departureConfigChanged = previous == null || previous.departureIdentity() != fresh.departureIdentity()
            _uiState.update {
                it.copy(
                    routine = fresh,
                    isPausedToday = fresh.pausedDate == today(),
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
            // The edit may also have changed enabled/schedule/pause fields, which affects
            // when this routine's active window should next run — always recompute, not only
            // when the departure-relevant identity changed.
            routineScheduler.scheduleActivation(fresh)
            // Best-effort -- this whole reload() coroutine has no try/catch of its own
            // (nothing above it can meaningfully "fail" from the user's perspective), so an
            // uncaught widget/Glance/DataStore failure here would otherwise crash the app via
            // viewModelScope's lack of a default exception handler (see runWidgetUpdateSafely's
            // own doc).
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
                val updated = routine.copy(enabled = newEnabled)
                if (newEnabled) {
                    routineScheduler.scheduleActivation(updated)
                } else {
                    routineScheduler.cancelActivation(updated.id)
                }
                _uiState.update {
                    it.copy(isTogglingEnabled = false, routine = it.routine?.copy(enabled = newEnabled))
                }
                // Best-effort, and deliberately AFTER the success state above is already
                // applied -- without runWidgetUpdateSafely, a widget/Glance/DataStore failure
                // here would fall into the `catch (e: Exception)` below and overwrite that
                // already-correct success state with enabledActionFailed = true, even though
                // setEnabled/scheduleActivation genuinely already succeeded (see
                // runWidgetUpdateSafely's own doc).
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
        val today = today()
        _uiState.update { it.copy(isTogglingPause = true, pauseActionFailed = false) }
        viewModelScope.launch {
            try {
                routineRepository.pauseForDate(routine.id, today)
                // Recompute the next eligible activation excluding today -- see
                // NextOccurrenceCalculator's excludedDate parameter.
                routineScheduler.scheduleActivation(routine.copy(pausedDate = today))
                _uiState.update {
                    it.copy(isTogglingPause = false, isPausedToday = true, routine = it.routine?.copy(pausedDate = today))
                }
                // Best-effort, deliberately after the success state above -- see toggleEnabled's
                // identical comment and runWidgetUpdateSafely's own doc.
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
                routineScheduler.scheduleActivation(routine.copy(pausedDate = null))
                _uiState.update {
                    it.copy(isTogglingPause = false, isPausedToday = false, routine = it.routine?.copy(pausedDate = null))
                }
                // Best-effort, deliberately after the success state above -- see toggleEnabled's
                // identical comment and runWidgetUpdateSafely's own doc.
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
                routineScheduler.cancelActivation(routine.id)
                routineNotifier.remove()
                // Best-effort, deliberately BEFORE the success state/onDeleted() below -- without
                // runWidgetUpdateSafely, a widget/Glance/DataStore failure here would fall into
                // the `catch (e: Exception)` below, report deleteFailed = true, and never call
                // onDeleted(), even though the routine was already genuinely deleted (see
                // runWidgetUpdateSafely's own doc and toggleEnabled's identical comment).
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
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
