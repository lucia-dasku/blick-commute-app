package se.blick.app.scheduling

import se.blick.app.domain.model.CommuteRoutine

/**
 * Represents scheduling a routine's active window (see the product doc's "Scheduling
 * and Android limitations" section, and [WorkManagerRoutineScheduler] for the concrete
 * implementation). [scheduleActivation] is idempotent and replacing: calling it again for the
 * same [CommuteRoutine.id] (after an edit, a pause/resume, an enable/disable, or simply to
 * recompute the next occurrence once a window ends) always supersedes any previously scheduled
 * work for that routine rather than leaving a second, obsolete activation pending — see
 * [WorkManagerRoutineScheduler]'s use of `ExistingWorkPolicy.REPLACE` against a unique,
 * per-routine work name.
 *
 * Deliberately never guarantees an exact start time — see [NextOccurrenceCalculator] and
 * [WorkManagerRoutineScheduler]'s own doc comments on why this is a best-effort activation
 * under ordinary Android scheduling deferral, not an exact alarm.
 */
interface RoutineScheduler {
    /** Schedules (or reschedules, replacing any existing pending activation for this routine
     * id) the routine's next eligible active window. A disabled routine, or one with no
     * [CommuteRoutine.activeDays], has any existing scheduled work cancelled instead — there
     * is nothing to activate. */
    fun scheduleActivation(routine: CommuteRoutine)

    /** Cancels any pending or in-flight scheduled activation for this routine id — a no-op if
     * none exists. Does not stop an already-running worker's current notification loop by
     * itself; [se.blick.app.notification.RoutineNotifier.remove] plus the worker's own
     * mid-run enabled/existence re-check (see [RoutineActiveWindowWorker]) handle that. */
    fun cancelActivation(routineId: String)

    /** Reports whether this routine's active-window worker is genuinely `RUNNING` right now —
     * used by [NotificationRecoveryCoordinator] to decide whether it is safe to call
     * [scheduleActivation] at all: that call's `ExistingWorkPolicy.REPLACE` (see
     * [WorkManagerRoutineScheduler]'s own doc) would otherwise cancel and replace an
     * already-running worker merely because something else wanted to make sure a routine's
     * active window is covered. Callers must treat a `false` result as "safe to schedule", not
     * as a hard guarantee — there is an inherent, unavoidable race between this query and any
     * subsequent [scheduleActivation] call, since WorkManager exposes no atomic
     * "replace-unless-running" primitive (see [NotificationRecoveryCoordinator]'s own doc on why
     * this is an accepted, narrow risk rather than one this method can eliminate outright). */
    suspend fun isActivationRunning(routineId: String): Boolean
}
