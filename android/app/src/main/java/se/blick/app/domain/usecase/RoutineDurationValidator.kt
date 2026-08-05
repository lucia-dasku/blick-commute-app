package se.blick.app.domain.usecase

import se.blick.app.domain.model.CommuteRoutine
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * Android limits a `dataSync` foreground service (see
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own class doc) to six combined hours per
 * rolling 24 hours while Blick is in the background. Five hours is a deliberately conservative
 * application-level ceiling under that platform limit — shared by the create/edit validation
 * ([RoutineDurationValidator]) and the defensive scheduling checks
 * ([se.blick.app.scheduling.WorkManagerRoutineScheduler],
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]) so every caller agrees on the same number.
 */
const val MAX_DAILY_ACTIVE_MINUTES = 5 * 60

/**
 * Result of [RoutineDurationValidator.validate] — a plain typed value, never a thrown exception:
 * exceeding the daily limit is an expected, user-correctable outcome of ordinary form input, not
 * a programming error.
 */
sealed interface RoutineDurationValidationResult {
    data object Valid : RoutineDurationValidationResult

    /** [weekday] is the first day (in [DayOfWeek] iteration order) found to exceed the limit —
     * there may be others; callers that only need one fixed user-facing message (see
     * `RoutineCreateScreen`) don't need every offending day, just whether any exist. */
    data class ExceedsDailyLimit(val weekday: DayOfWeek, val totalMinutes: Int) : RoutineDurationValidationResult
}

/**
 * Validates that a proposed routine's active-window duration, combined with every OTHER
 * currently enabled routine active on the same weekday, never exceeds [MAX_DAILY_ACTIVE_MINUTES]
 * for any single weekday. Pure and side-effect-free — callers load [existingRoutines] themselves
 * (typically via [se.blick.app.data.repository.RoutineRepository.observeAll]) and interpret the
 * result; this never touches Room, WorkManager, or the UI, so it is usable identically from the
 * create/edit save flow and from the scheduling/reconciliation path (see [validateSelf]).
 *
 * Each weekday is evaluated independently — a routine spanning Monday and Tuesday only competes
 * for Monday's budget against other routines also active on Monday, never against a
 * Tuesday-only routine's duration.
 *
 * Sums each qualifying routine's own configured (end − start) duration rather than computing the
 * union of their time ranges. Two routines active at the very same time still each run their OWN
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] instance — separate foreground execution,
 * separate ~30s network loop — so they consume Android's six-hour `dataSync` budget additively,
 * not merely for whichever span of wall-clock time they happen to overlap. This is deliberately
 * conservative: if a future change ever makes overlapping routines share one worker, this rule
 * should be revisited alongside it.
 */
object RoutineDurationValidator {

    fun validate(
        proposedRoutineId: String?,
        proposedStartTime: LocalTime,
        proposedEndTime: LocalTime,
        proposedActiveDays: Set<DayOfWeek>,
        proposedEnabled: Boolean,
        existingRoutines: List<CommuteRoutine>,
    ): RoutineDurationValidationResult {
        // A disabled proposed routine has no scheduled activation at all (see
        // RoutineScheduler.scheduleActivation) and so can never contribute foreground runtime,
        // regardless of its configured duration.
        if (!proposedEnabled) return RoutineDurationValidationResult.Valid

        val proposedMinutes = Duration.between(proposedStartTime, proposedEndTime).toMinutes()
        // A non-positive duration is a different validation's concern (RoutineCreateUiState.
        // isTimeRangeValid) -- this validator only ever adds a non-negative contribution.
        if (proposedMinutes <= 0) return RoutineDurationValidationResult.Valid

        for (day in proposedActiveDays) {
            val othersMinutes = existingRoutines
                .asSequence()
                .filter { it.enabled }
                .filter { it.id != proposedRoutineId }
                .filter { day in it.activeDays }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val totalMinutes = othersMinutes + proposedMinutes
            if (totalMinutes > MAX_DAILY_ACTIVE_MINUTES) {
                return RoutineDurationValidationResult.ExceedsDailyLimit(day, totalMinutes.toInt())
            }
        }
        return RoutineDurationValidationResult.Valid
    }

    /**
     * Defensive, self-contained check used at the scheduling layer (see
     * [se.blick.app.scheduling.WorkManagerRoutineScheduler.scheduleActivation] and
     * [se.blick.app.scheduling.RoutineActiveWindowWorker.doWork]): whether [routine], entirely on
     * its own, respects [MAX_DAILY_ACTIVE_MINUTES]. Equivalent to calling [validate] with an
     * empty existing-routines list.
     *
     * Deliberately does not re-derive the full cross-routine combined total: doing so would
     * require loading every other routine from Room at every reschedule (startup, reboot,
     * timezone change, end of every window), for a check that only matters at the moment a user
     * is about to introduce a NEW commitment. A routine already saved was already validated
     * against every other routine at that moment (see [validate]'s own callers); this narrower
     * check exists purely to catch a routine that is invalid entirely on its own — e.g. one
     * saved before this limit existed, or written by a future code change or a corrupted/edited
     * database — without a crash and without silently shortening it.
     */
    fun validateSelf(routine: CommuteRoutine): RoutineDurationValidationResult = validate(
        proposedRoutineId = routine.id,
        proposedStartTime = routine.startTime,
        proposedEndTime = routine.endTime,
        proposedActiveDays = routine.activeDays,
        proposedEnabled = routine.enabled,
        existingRoutines = emptyList(),
    )
}
