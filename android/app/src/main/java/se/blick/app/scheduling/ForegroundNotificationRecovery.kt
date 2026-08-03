package se.blick.app.scheduling

import kotlinx.coroutines.flow.first
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationAvailabilityStateStore
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces an earlier, over-eager fix that ran [RoutineScheduleReconciler.reconcileAll] on
 * every single app foreground (`BlickApplication`'s `ProcessLifecycleOwner` `ON_START`
 * observer). That call enqueues every enabled routine's activation via
 * [RoutineScheduler.scheduleActivation], whose `ExistingWorkPolicy.REPLACE` (see
 * [WorkManagerRoutineScheduler]'s own doc) can cancel and replace an already-`RUNNING`
 * [RoutineActiveWindowWorker] merely because the user opened the app — a real regression
 * causing notification/widget flicker, duplicate departures/disruptions API requests, loss of
 * the worker's in-memory `lastKnownDisruption` fallback, and a genuine race where the
 * CANCELLED worker's own `finally` block (see that class's doc) removes/clears notification and
 * widget content the REPLACEMENT worker had already started posting.
 *
 * [onForeground] is transition-aware and a no-op on the overwhelmingly common case — nothing
 * changed: it persists the current [NotificationAvailability] snapshot via
 * [stateStore] specifically so a genuine unavailable-to-available transition can be detected
 * across process recreation (a plain in-memory field, like `RoutineDetailsViewModel`'s own
 * `notificationAvailability` state, resets exactly when this matters most — see
 * [NotificationAvailabilityStateStore]'s own doc) — and touches nothing else at all unless
 * [stateStore] shows the last recorded snapshot was NOT available and the current one is. A
 * missing (`null`) prior snapshot — a fresh install, or before this store's first-ever write —
 * is treated as "no transition", not as "was unavailable": the app's own cold-start
 * reconciliation (`BlickApplication.onCreate` calling [RoutineScheduleReconciler.reconcileAll]
 * once, unconditionally) already covers that case.
 *
 * On a genuine transition, [recoverActiveWindows] is deliberately narrower than
 * [RoutineScheduleReconciler.reconcileAll]: for each enabled routine currently inside its own
 * active window ([NextOccurrence.ActiveNow] — recomputed fresh here, independently of whatever
 * WorkManager currently has enqueued), it starts the missing worker ONLY if
 * [RoutineScheduler.isActivationRunning] reports nothing already running for that routine — an
 * already-`RUNNING` worker is left completely untouched: never re-enqueued, never replaced, its
 * work id and state undisturbed. A routine outside its active window is left alone entirely —
 * its existing future schedule is already correct, and this path's whole purpose is resuming a
 * MISSED active window, not general reconciliation (that remains [RoutineScheduleReconciler]'s
 * job, run only at process start and on a device timezone change).
 *
 * The check-then-enqueue sequence is not perfectly atomic — WorkManager exposes no atomic
 * "replace unless running" primitive — so there is an unavoidable, narrow race where a worker
 * transitions from not-yet-started to actually `RUNNING` in the brief window between
 * [RoutineScheduler.isActivationRunning]'s query and this class's own
 * [RoutineScheduler.scheduleActivation] call. That residual window is vanishingly narrow
 * compared to the bug being fixed here (which fired on literally every foreground, regardless
 * of state, guaranteed to hit a `RUNNING` worker sooner or later) — the same class of
 * best-effort race every other non-exact WorkManager scheduling call in this codebase already
 * accepts (see [WorkManagerRoutineScheduler]'s own doc on ordinary Doze/App-Standby deferral).
 */
@Singleton
class ForegroundNotificationRecovery @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val stateStore: NotificationAvailabilityStateStore,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) {
    suspend fun onForeground() {
        val previouslyAvailable = stateStore.lastKnownAvailable.first()
        val currentlyAvailable = notificationAvailabilityChecker.check() == NotificationAvailability.Available
        stateStore.setLastKnownAvailable(currentlyAvailable)

        if (previouslyAvailable == false && currentlyAvailable) {
            recoverActiveWindows()
        }
    }

    private suspend fun recoverActiveWindows() {
        val now = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())
        routineRepository.observeAll().first().forEach { routine ->
            if (!routine.enabled) return@forEach
            val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, now, excludedDate = routine.pausedDate)
            if (occurrence is NextOccurrence.ActiveNow && !routineScheduler.isActivationRunning(routine.id)) {
                routineScheduler.scheduleActivation(routine)
            }
        }
        // Best-effort: a widget/Glance/DataStore failure here must never crash whatever
        // triggered this (the ProcessLifecycleOwner ON_START callback) or mask the scheduling
        // work already done above -- see runWidgetUpdateSafely's own doc.
        runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
    }
}
