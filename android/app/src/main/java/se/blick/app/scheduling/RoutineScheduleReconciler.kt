package se.blick.app.scheduling

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "RoutineScheduleReconciler"

/**
 * Re-derives and re-enqueues every saved, enabled routine's next active-window activation
 * against the CURRENT clock and device zone, UNCONDITIONALLY — including a routine whose worker
 * is genuinely `RUNNING` right now (see [RoutineScheduler.scheduleActivation]'s own
 * `ExistingWorkPolicy.REPLACE`). Cheap and idempotent from this class's own point of view — safe
 * to call as often as needed — but that unconditional replacement is exactly why its only
 * remaining caller today, [NotificationRecoveryCoordinator.onTimeZoneChanged], invokes it
 * specifically for a device-timezone change: WorkManager's own already-enqueued `initialDelay`
 * cannot retroactively account for a live zone change, since that delay was fixed in wall-clock
 * terms against whichever zone [DeviceZoneProvider] returned the last time
 * [RoutineScheduler.scheduleActivation] ran for that routine — and the routine's own configured
 * [java.time.LocalTime] start/end must be reinterpreted against the NEW zone even for a window
 * that is ActiveNow right now, so replacing a running worker here is correct, not merely
 * tolerated (see [NotificationRecoveryCoordinator]'s own doc for the full contrast with its
 * OTHER two callers, [NotificationRecoveryCoordinator.onAppStart]/`onForeground`, which
 * deliberately do NOT replace an already-running worker, and therefore do not call this class at
 * all).
 *
 * Also reconciles the home-screen widget (see [RoutineWidgetUpdater.reconcile]) every time this
 * runs, for the same reason: a device-timezone change is exactly the kind of event that could
 * make a routine's active window start or end from one moment to the next with nothing else
 * running to keep the widget honest.
 *
 * Reading the routine list itself, and [routineScheduler.scheduleActivation] for each one, are
 * both wrapped in their own try/catch (a genuine [CancellationException] always rethrows
 * unconverted, exactly like every other coroutine-cancellation handling in this codebase)
 * rather than letting a Room read failure, a corrupted routine record, or a WorkManager call
 * throwing on some OEM propagate uncaught and crash the app every time a timezone-change
 * broadcast is delivered, for as long as the underlying data problem persisted. Scheduling is
 * per-routine specifically so one bad record can't also silently leave every OTHER routine
 * later in iteration order unscheduled — logged and skipped instead, so the rest of the batch
 * still gets a fair attempt.
 */
@Singleton
class RoutineScheduleReconciler @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
) {
    suspend fun reconcileAll() {
        val routines = try {
            routineRepository.observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read the routine list during reconcile; scheduling nothing this pass", e)
            emptyList()
        }
        routines.forEach { routine ->
            if (!routine.enabled) return@forEach
            try {
                routineScheduler.scheduleActivation(routine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to schedule routine ${routine.id} during reconcile; continuing with the rest", e)
            }
        }
        // Best-effort: a widget/Glance/DataStore failure here must never make the scheduling
        // loop above look like it failed too, or crash NotificationRecoveryCoordinator's own
        // onTimeZoneChanged, the sole caller that invokes this -- see runWidgetUpdateSafely's
        // own doc.
        runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
    }
}
