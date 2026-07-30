package se.blick.app.scheduling

import kotlinx.coroutines.flow.first
import se.blick.app.data.repository.RoutineRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-derives and re-enqueues every saved, enabled routine's next active-window activation
 * against the CURRENT clock and device zone. Cheap and idempotent —
 * [RoutineScheduler.scheduleActivation] always replaces rather than duplicates any existing
 * scheduled work for the same routine id — so this is safe to call as often as needed:
 *
 * - Once at process start (see `BlickApplication.onCreate`), covering reboot, an app update,
 *   and ordinary process recreation. WorkManager itself already persists enqueued work across
 *   all of these without help, so this is a defensive backstop, not the primary scheduling
 *   path — saving, editing, enabling, disabling, pausing, or resuming a routine already calls
 *   [RoutineScheduler] directly at the point of that change.
 * - Every time the device's time zone changes while the process stays alive (see
 *   `BlickApplication`'s `Intent.ACTION_TIMEZONE_CHANGED` receiver) — a change that WorkManager's
 *   own already-enqueued `initialDelay` cannot retroactively account for, since that delay was
 *   fixed in wall-clock terms against whichever zone [DeviceZoneProvider] returned the last time
 *   [RoutineScheduler.scheduleActivation] ran for that routine. Re-running this reconciliation
 *   recomputes every routine's next window against the new zone immediately, rather than
 *   leaving it silently wrong until the routine is next edited or the process restarts.
 */
@Singleton
class RoutineScheduleReconciler @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
) {
    suspend fun reconcileAll() {
        routineRepository.observeAll().first().forEach { routine ->
            if (routine.enabled) routineScheduler.scheduleActivation(routine)
        }
    }
}
