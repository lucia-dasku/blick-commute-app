package se.blick.app.scheduling

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.MAX_DAILY_ACTIVE_MINUTES
import se.blick.app.domain.usecase.RoutineDurationValidationResult
import se.blick.app.domain.usecase.RoutineDurationValidator
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "WorkManagerRoutineScheduler"

/**
 * WorkManager-backed [RoutineScheduler]. Uses exactly one mechanism — a single
 * [androidx.work.OneTimeWorkRequest] per routine, enqueued as unique work — for both "wait
 * until the next active window" and, once a window ends, "wait until the one after that" (see
 * [RoutineActiveWindowWorker], which re-schedules itself via this same class when its loop
 * finishes). There is deliberately no [androidx.work.PeriodicWorkRequest] anywhere: periodic
 * work's minimum interval (15 minutes) cannot represent this app's 30-second in-window refresh,
 * so that refresh is a plain `delay()` loop inside the worker itself instead (see
 * [RoutineActiveWindowWorker]'s doc comment).
 *
 * `ExistingWorkPolicy.REPLACE` against [uniqueWorkName] is what makes [scheduleActivation]
 * safe to call repeatedly for the same routine (on every save/edit/enable/disable/pause/resume,
 * and again each time a window finishes) without ever leaving a stale, obsolete activation
 * enqueued alongside a newer one.
 *
 * Never schedules anything "exactly" — [initialDelay] is computed from
 * [NextOccurrenceCalculator]'s best-effort target, and WorkManager itself only guarantees a
 * work request runs at or after that delay, subject to ordinary Doze/App-Standby/JobScheduler
 * deferral like any other non-exact work. No `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` permission
 * is requested anywhere in this codebase, matching the product doc's documented Android
 * scheduling limitation.
 *
 * [now] is deliberately built from [clock]'s instant combined with [deviceZoneProvider]'s
 * CURRENT zone (via `ZonedDateTime.ofInstant`) rather than `ZonedDateTime.now(clock)` — the
 * latter would resolve the routine's wall-clock weekday/start/end time against [clock]'s own
 * zone, which is `Clock.systemUTC()` in production (see `di/TimeModule.kt`), silently
 * scheduling a Stockholm 07:30 routine as 07:30 UTC. Re-reading [deviceZoneProvider] on every
 * call (rather than capturing a zone once) is also what makes a live device timezone change
 * take effect on the very next [scheduleActivation] call — see `BlickApplication`'s
 * `ACTION_TIMEZONE_CHANGED` receiver, which calls back into this via
 * `RoutineScheduleReconciler`.
 */
@Singleton
class WorkManagerRoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) : RoutineScheduler {

    override fun scheduleActivation(routine: CommuteRoutine) {
        val workManager = WorkManager.getInstance(context)

        // Defensive re-check, independent of whatever validated this routine when it was
        // created/edited (see RoutineDurationValidator's own doc) -- an old database, a
        // corrupted record, or a future code change could still contain a routine longer than
        // MAX_DAILY_ACTIVE_MINUTES. Every scheduling/reconciliation entry point (save, enable/
        // disable/pause/resume, RoutineScheduleReconciler, NotificationRecoveryCoordinator --
        // which is itself the sole path for app start, foreground, and timezone-change recovery)
        // funnels through this one method, so checking here covers all of them uniformly. Never
        // silently shortens the routine or
        // touches Room -- it stays exactly as stored so the user can correct it in the app; only
        // its WorkManager scheduling is refused.
        val durationValidation = RoutineDurationValidator.validateSelf(routine)
        if (durationValidation is RoutineDurationValidationResult.ExceedsDailyLimit) {
            Log.w(
                LOG_TAG,
                "Routine ${routine.id} exceeds the $MAX_DAILY_ACTIVE_MINUTES-minute daily " +
                    "active-duration limit (${durationValidation.totalMinutes}min on " +
                    "${durationValidation.weekday}); refusing to schedule it. Leaving it stored " +
                    "so the user can correct it.",
            )
            logCancellationRequest(routine.id, "invalid_duration")
            workManager.cancelUniqueWork(uniqueWorkName(routine.id))
            return
        }

        val now = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())
        val excludedDate = routine.pausedDate

        val occurrence = if (routine.enabled) {
            NextOccurrenceCalculator.nextOccurrence(routine, now, excludedDate = excludedDate)
        } else {
            NextOccurrence.None
        }

        val initialDelay = when (occurrence) {
            is NextOccurrence.ActiveNow -> Duration.ZERO
            is NextOccurrence.Upcoming -> Duration.between(now, occurrence.windowStart)
            NextOccurrence.None -> {
                val reason = when {
                    !routine.enabled -> "routine_disabled"
                    routine.pausedDate != null -> "routine_paused_or_no_eligible_occurrence"
                    else -> "no_eligible_occurrence"
                }
                logCancellationRequest(routine.id, reason)
                workManager.cancelUniqueWork(uniqueWorkName(routine.id))
                return
            }
        }

        val request = OneTimeWorkRequestBuilder<RoutineActiveWindowWorker>()
            .setInitialDelay(initialDelay)
            .setInputData(workDataOf(RoutineActiveWindowWorker.KEY_ROUTINE_ID to routine.id))
            .addTag(WORK_TAG)
            .build()

        val scheduleReason = if (routine.pausedDate != null) {
            "routine_paused_reschedule"
        } else {
            "initial_or_replacement_reschedule"
        }
        Log.i(
            LOG_TAG,
            "action=enqueue_replace routineId=${routine.id} workerId=${request.id} " +
                "scheduleReason=$scheduleReason sdk=${Build.VERSION.SDK_INT}",
        )
        workManager.enqueueUniqueWork(uniqueWorkName(routine.id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelActivation(routineId: String) {
        cancelActivation(routineId, RoutineCancellationReason.EXPLICIT_APP_CANCELLATION)
    }

    override fun cancelActivation(routineId: String, reason: RoutineCancellationReason) {
        logCancellationRequest(routineId, reason.name)
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(routineId))
    }

    /** [WorkManager.getWorkInfosForUniqueWork] returns a blocking [com.google.common.util.concurrent.ListenableFuture]
     * — its own `.get()` is dispatched onto [Dispatchers.IO] rather than blocking whichever
     * dispatcher this suspend function happens to be called from. */
    override suspend fun isActivationRunning(routineId: String): Boolean = withContext(Dispatchers.IO) {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(uniqueWorkName(routineId))
            .get()
        workInfos.forEach { workInfo ->
            Log.i(
                LOG_TAG,
                formatWorkInfoDiagnostic(
                    routineId = routineId,
                    workerId = workInfo.id.toString(),
                    state = workInfo.state.name,
                    stopReason = workInfoStopReasonOrNull(workInfo),
                    sdkInt = Build.VERSION.SDK_INT,
                ),
            )
        }
        workInfos.any { it.state == WorkInfo.State.RUNNING }
    }

    private fun logCancellationRequest(routineId: String, reason: String) {
        Log.i(
            LOG_TAG,
            "action=cancel_requested routineId=$routineId cancellationReason=$reason " +
                "sdk=${Build.VERSION.SDK_INT}",
        )
    }

    companion object {
        private const val WORK_NAME_PREFIX = "routine-active-window-"

        /** Shared tag (distinct from the per-routine unique work name) so every routine's
         * scheduled activation can be found/cancelled in bulk if ever needed, without knowing
         * every routine id in advance. */
        const val WORK_TAG = "routine-active-window"

        fun uniqueWorkName(routineId: String): String = "$WORK_NAME_PREFIX$routineId"
    }
}
