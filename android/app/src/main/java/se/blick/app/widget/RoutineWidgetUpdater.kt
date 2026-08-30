package se.blick.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import se.blick.app.billing.FreePremiumEntitlementRepository
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.RoutineTierPolicy
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.toPresentation
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.ui.theme.shouldUseStockholmNightTheme
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes [RoutineWidgetUiState] to every placed [BlickRoutineWidget] instance. Never fetches
 * departures, never schedules work, never runs a timer of its own — every call site already has
 * (or can cheaply derive) what it needs to call one of these three methods; see each method's
 * own doc for exactly which call sites use it.
 */
interface RoutineWidgetUpdater {
    /** [fetchFailed] — see [decideJourneysWidgetState]'s own doc — distinguishes a genuine
     * search failure from a search that succeeded and simply found nothing; defaults to `false`
     * so every existing fake/test that only cares about the common case keeps compiling
     * unchanged, the same reasoning as the four-argument [updateWithDepartures] overload's own
     * default body. */
    suspend fun updateWithJourneys(routine: CommuteRoutine, journeys: List<JourneyPlan>, now: Instant, fetchFailed: Boolean = false) {}

    /** Same as the four-argument [updateWithJourneys], plus [disruption] — the current PRIMARY
     * journey's own disruption presentation, already derived this same worker tick from the
     * same [journeys] this call already carries (see
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own doc) — no separate fetch, no
     * separate timer. Default implementation forwards to the four-argument overload, ignoring
     * [disruption] — the correct, behaviorally-unchanged choice for any implementation (test
     * fakes) that doesn't render a disruption strip; only [GlanceRoutineWidgetUpdater] overrides
     * this meaningfully. A separate overload, not an added parameter on the existing method,
     * specifically so no existing implementer needs to change at all — the same convention as
     * [updateWithDepartures]'s own three-argument/four-argument split. */
    suspend fun updateWithJourneys(
        routine: CommuteRoutine,
        journeys: List<JourneyPlan>,
        now: Instant,
        fetchFailed: Boolean,
        disruption: DisruptionPresentation?,
    ) {
        updateWithJourneys(routine, journeys, now, fetchFailed)
    }

    /** Called once per [se.blick.app.scheduling.RoutineActiveWindowWorker] loop tick, right
     * after [se.blick.app.notification.RoutineNotifier.showOrUpdate] — reuses the exact
     * [routine]/[departuresState]/[now] already fetched for the notification, via
     * [RoutineWidgetMapper]. No separate fetch, no separate 30-second timer. */
    suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant)

    /** Same as the three-argument [updateWithDepartures], plus [disruption] — the exact
     * highest-priority disruption already fetched this tick for the notification's own second,
     * disruption-aware [se.blick.app.notification.RoutineNotifier.showOrUpdate] call (see
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own doc). Default implementation
     * simply forwards to the three-argument overload, ignoring [disruption] — the correct,
     * behaviorally-unchanged choice for any implementation (test fakes) that doesn't render a
     * disruption strip; only [GlanceRoutineWidgetUpdater] overrides this meaningfully. Kept as a
     * separate overload, not an added parameter on the existing method, specifically so no
     * existing implementer needs to change at all. */
    suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant, disruption: Disruption?) {
        updateWithDepartures(routine, departuresState, now)
    }

    /** Called only from the worker's own `finally` block, mirroring
     * [se.blick.app.notification.RoutineNotifier.remove] exactly — the active window has just
     * ended normally or is no longer eligible, so the widget goes back to
     * [RoutineWidgetUiState.NoActiveCommute] unconditionally, with no extra lookup. An unexpected
     * worker exception uses [reconcile] instead because WorkManager will retry while the window
     * may still be active. */
    suspend fun clear()

    /** Called from every routine-lifecycle mutation site that happens OUTSIDE the worker's loop
     * (create/edit save, enable/disable, pause/resume, delete, the notification's own Stop
     * action), from [se.blick.app.scheduling.NotificationRecoveryCoordinator] (covering app
     * start, foreground, and notification-availability recovery — which itself also covers
     * reboot, since `Application.onCreate` always runs before any component executes in a
     * freshly-started process) and from [se.blick.app.scheduling.RoutineScheduleReconciler.reconcileAll]
     * (covering device-timezone change, via that same coordinator's own
     * `onTimeZoneChanged`), and from a freshly-placed widget instance's very first
     * [androidx.glance.appwidget.GlanceAppWidgetReceiver.onUpdate] (see
     * `BlickRoutineWidgetReceiver`) — re-derives the correct widget state from scratch via
     * [decideReconciledWidgetState], including [RoutineWidgetContent.NotificationsUnavailable]
     * when a window is active but
     * [se.blick.app.notification.NotificationAvailabilityChecker] reports unavailable, since
     * none of those call sites have fresh departure data in hand the way the worker's loop
     * does. */
    suspend fun reconcile()

    /** Called when [routine]'s active window is genuinely open right now but
     * [se.blick.app.notification.NotificationAvailabilityChecker] reports notifications are
     * unavailable — either before [se.blick.app.scheduling.RoutineActiveWindowWorker] ever enters
     * its loop, or discovered mid-loop and about to break out of it. Live widget updates
     * currently depend on notification availability (see
     * [RoutineWidgetContent.NotificationsUnavailable]'s own doc on why), so this represents that
     * honestly instead of leaving the widget showing [RoutineWidgetContent.Loading] forever, or
     * silently going back to [RoutineWidgetUiState.NoActiveCommute] as if the window weren't
     * actually open. */
    suspend fun showNotificationsUnavailable(routine: CommuteRoutine)

    /** Redraws every already-placed widget instance using whatever [RoutineWidgetUiState] is
     * ALREADY persisted for it — no fetch, no state recomputation, and unlike [reconcile], no
     * risk of dropping an active widget to [RoutineWidgetContent.Loading]/[RoutineWidgetUiState.NoActiveCommute]
     * (see [reconcile]'s own doc: it re-derives state from scratch, which [decideReconciledWidgetState]
     * only ever reports as [RoutineWidgetContent.Loading] for a genuinely active window — never
     * safe to use for a purely presentational refresh). Call this when Blick's selected app
     * language or appearance changes: every placed instance should re-render its CURRENT content
     * through freshly resolved presentation resources, without touching what that content says.
     *
     * Defaults to a no-op so every existing [RoutineWidgetUpdater] fake across this codebase's
     * tests keeps compiling unchanged (the same reasoning as the four-argument
     * [updateWithDepartures] overload's own default body) — only [GlanceRoutineWidgetUpdater]
     * overrides this meaningfully. */
    suspend fun refreshPresentation() {}
}

private const val WIDGET_UPDATE_LOG_TAG = "RoutineWidgetUpdater"

/**
 * Runs [block] — expected to be exactly one [RoutineWidgetUpdater] call — swallowing any
 * ordinary exception it throws (logged, not silently dropped) but always rethrowing a genuine
 * [CancellationException] unconverted.
 *
 * The home-screen widget is a purely additive, best-effort surface (see this file's own class
 * docs): a Glance/DataStore failure updating it must never
 * - stop an already-successful notification post or end the active-window loop early
 *   ([se.blick.app.scheduling.RoutineActiveWindowWorker]'s own `catch (e: Exception)` would
 *   otherwise treat it as a "handled failure" and cut the whole window short even though the
 *   notification itself posted fine),
 * - prevent rescheduling ([se.blick.app.scheduling.RoutineScheduleReconciler]/the worker's own
 *   reschedule calls already ran by the time most of these calls happen, but an uncaught
 *   exception here would still fail the surrounding function/coroutine),
 * - crash an unguarded coroutine — `viewModelScope.launch { }` and a `BroadcastReceiver`'s own
 *   detached `CoroutineScope(...).launch { }` (see
 *   [se.blick.app.notification.StopRoutineNotificationReceiver]) have no default exception
 *   handler on Android, so an uncaught exception there crashes the whole app, not just this one
 *   operation, or
 * - cause a genuinely successful create/edit/delete/enable/disable/pause/resume/Stop action to
 *   be reported back to the user as failed — several ViewModel functions call
 *   [RoutineWidgetUpdater.reconcile] as the LAST step after the real repository/scheduler work
 *   has already succeeded; without this wrapper, a widget failure there would land in the same
 *   `catch` block that marks the whole action failed, even though the actual mutation the user
 *   asked for already went through.
 *
 * Every call site that touches [RoutineWidgetUpdater] wraps that call with this function rather
 * than letting it participate in the caller's own success/failure control flow.
 */
suspend fun runWidgetUpdateSafely(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(WIDGET_UPDATE_LOG_TAG, "Widget update failed; ignoring (best-effort, see runWidgetUpdateSafely's own doc)", e)
    }
}

@Singleton
class GlanceRoutineWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routineRepository: RoutineRepository,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore? = null,
) : RoutineWidgetUpdater {

    override suspend fun updateWithJourneys(routine: CommuteRoutine, journeys: List<JourneyPlan>, now: Instant, fetchFailed: Boolean) {
        applyToAllInstances(decideJourneysWidgetState(routine, journeys, now, fetchFailed))
    }

    override suspend fun updateWithJourneys(
        routine: CommuteRoutine,
        journeys: List<JourneyPlan>,
        now: Instant,
        fetchFailed: Boolean,
        disruption: DisruptionPresentation?,
    ) {
        applyToAllInstances(decideJourneysWidgetState(routine, journeys, now, fetchFailed, disruption))
    }

    override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
        updateWithDepartures(routine, departuresState, now, disruption = null)
    }

    override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant, disruption: Disruption?) {
        applyToAllInstances(RoutineWidgetUiState.ActiveRoutine(RoutineWidgetMapper.map(routine, departuresState, now, disruption?.toPresentation())))
    }

    override suspend fun clear() {
        applyToAllInstances(RoutineWidgetUiState.NoActiveCommute)
    }

    override suspend fun reconcile() {
        val now = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())
        val routines = routineRepository.observeAll().first()
        val eligibleRoutines = routines.filter { routine ->
            RoutineTierPolicy.canRun(
                routine, routines, entitlementRepository.entitlement.value,
                freeRoutineSelectionStore?.selectedRoutineId?.value,
            )
        }
        val notificationsAvailable = notificationAvailabilityChecker.check() == NotificationAvailability.Available
        applyToAllInstances(decideReconciledWidgetState(eligibleRoutines, now, notificationsAvailable))
    }

    override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) {
        applyToAllInstances(RoutineWidgetUiState.ActiveRoutine(RoutineWidgetMapper.notificationsUnavailable(routine)))
    }

    /** Deliberately does NOT go through [applyToAllInstances], so the current
     * [RoutineWidgetUiState] remains untouched. This updates only the effective appearance flag,
     * then re-triggers [BlickRoutineWidget.provideGlance] for every already-placed instance. */
    override suspend fun refreshPresentation() {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(BlickRoutineWidget::class.java)
        if (ids.isEmpty()) return
        val useStockholmNightTheme = effectiveStockholmNightTheme()
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs.setStockholmNightWidgetTheme(useStockholmNightTheme)
            }
        }
        BlickRoutineWidget().updateAll(context)
    }

    private suspend fun applyToAllInstances(state: RoutineWidgetUiState) {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(BlickRoutineWidget::class.java)
        if (ids.isEmpty()) return
        val useStockholmNightTheme = effectiveStockholmNightTheme()
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                state.writeInto(prefs)
                prefs.setStockholmNightWidgetTheme(useStockholmNightTheme)
            }
        }
        BlickRoutineWidget().updateAll(context)
    }

    private suspend fun effectiveStockholmNightTheme(): Boolean = shouldUseStockholmNightTheme(
        requested = appSettingsDataStore.settings.first().useStockholmNightTheme,
        hasPremiumAccess = entitlementRepository.entitlement.value.hasPremiumAccess,
    )
}
