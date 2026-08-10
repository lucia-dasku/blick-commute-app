package se.blick.app.domain.usecase

import se.blick.app.domain.model.CommuteRoutine
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Android limits a `dataSync` foreground service (see
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own class doc) to six combined hours
 * (360 minutes) per rolling 24 hours while Blick is in the background — see
 * [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
 * 300 minutes (5 hours) is a deliberately conservative application-level ceiling under that
 * platform limit — shared by the create/edit validation ([RoutineDurationValidator]) and the
 * defensive scheduling checks ([se.blick.app.scheduling.WorkManagerRoutineScheduler],
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]) so every caller agrees on the same number.
 *
 * This validator is deliberately timezone-naive: it models every occurrence purely from the
 * routine's own configured [java.time.LocalTime]s and [java.time.DayOfWeek]s, on a synthetic
 * repeating week where consecutive days are always exactly 1440 minutes apart (see [validate]'s
 * own doc). Real clocks are not always 1440 minutes apart across a daylight-saving transition, so
 * a 5-hour-configured occurrence can genuinely take longer than 5 real hours to elapse, or land
 * closer to a neighboring occurrence than this model assumes. That real-elapsed-time risk is
 * handled separately, downstream, by
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own `HARD_FOREGROUND_RUNTIME_CAP_MINUTES`
 * — a real-elapsed-time backstop (measured via [android.os.SystemClock.elapsedRealtime], never
 * wall-clock time) that both stops a single occurrence outright if it runs too long, and reduces
 * its own effective cap for the specific occurrence immediately before a daylight-saving-shortened
 * gap to the next one — see that constant's own doc for exactly how. This validator's job is only
 * ever the CONFIGURED ceiling in local clock time; it does not need to know about DST at all.
 */
const val MAX_DAILY_ACTIVE_MINUTES = 5 * 60

private const val MINUTES_PER_DAY = 24 * 60
private const val CYCLE_DAYS = 7
private const val CYCLE_MINUTES = MINUTES_PER_DAY * CYCLE_DAYS

/** 0-indexed Monday..Sunday, matching [DayOfWeek.of]'s own 1-indexed inverse used when reporting
 * a violation below. */
private fun DayOfWeek.cycleIndex(): Int = value - 1

private fun LocalTime.minuteOfDay(): Int = (toSecondOfDay() / 60)

internal fun activeWindowDurationMinutes(start: LocalTime, end: LocalTime): Int {
    val startMinute = start.minuteOfDay()
    val endMinute = end.minuteOfDay()
    return if (endMinute > startMinute) endMinute - startMinute else endMinute + MINUTES_PER_DAY - startMinute
}

private data class Occurrence(val cycleStartMinute: Int, val durationMinutes: Int)

/**
 * Result of [RoutineDurationValidator.validate] — a plain typed value, never a thrown exception:
 * exceeding the daily limit is an expected, user-correctable outcome of ordinary form input, not
 * a programming error.
 */
sealed interface RoutineDurationValidationResult {
    data object Valid : RoutineDurationValidationResult

    /** [weekday] is the day on which the worst-case rolling 24-hour window (see [validate]'s own
     * doc) starts; [totalMinutes] is that window's combined total. There may be other violating
     * windows — callers that only need one fixed user-facing message (see `RoutineCreateScreen`)
     * don't need every one, just whether any exist. */
    data class ExceedsDailyLimit(val weekday: DayOfWeek, val totalMinutes: Int) : RoutineDurationValidationResult
}

/**
 * Validates that a proposed routine's active-window duration, combined with every OTHER
 * currently enabled routine, never lets ANY rolling 24-hour window's combined total exceed
 * [MAX_DAILY_ACTIVE_MINUTES] — matching Android's own dataSync accounting, which measures six
 * hours in any rolling 24-hour period, not per calendar day. Pure and side-effect-free — callers
 * load [existingRoutines] themselves (typically via
 * [se.blick.app.data.repository.RoutineRepository.observeAll]) and interpret the result; this
 * never touches Room, WorkManager, or the UI, so it is usable identically from the create/edit
 * save flow and from the scheduling/reconciliation path (see [validateSelf]).
 *
 * A calendar-day-only check (summing whichever routines are active on the SAME weekday) would
 * miss a real rolling-24h violation spanning two adjacent days — e.g. a routine active Monday
 * 18:00–23:00 (5h) and another active Tuesday 00:00–05:00 (5h): each day's own total is only 5h,
 * but the two occurrences sit only an hour apart, so a window from Monday 18:00 to Tuesday 18:00
 * contains both in full — 10 real configured hours inside one rolling 24-hour span. This
 * validator finds that window (and every other) by modeling each routine occurrence on a
 * repeating weekly timeline and scanning every possible 24-hour window's combined total via a
 * difference-array sweep, rather than only checking each day in isolation. A same-weekday
 * violation is always found too, since the window starting at that day's own midnight always
 * captures every occurrence entirely contained within it — so this subsumes the simpler
 * same-day check rather than needing it separately.
 *
 * A disabled proposal is exempt only from COMBINING with other routines (it has no scheduled
 * activation at all — see [se.blick.app.scheduling.RoutineScheduler.scheduleActivation] — so it
 * cannot contribute foreground runtime alongside them right now). Its OWN configured duration is
 * still checked unconditionally, regardless of [proposedEnabled]: a routine disabled today can be
 * re-enabled later without going through this validator again (see
 * `RoutineDetailsViewModel.toggleEnabled`, which writes `enabled = true` and merely asks
 * [se.blick.app.scheduling.RoutineScheduler] to schedule it) — if an over-limit duration were
 * ever allowed to save while disabled, re-enabling it would silently fail to schedule (caught
 * only by [validateSelf]'s defensive check, deep in the scheduler, with no user-visible feedback)
 * even though the UI would report the toggle as a success. Rejecting the excessive duration up
 * front, whether enabled or not, means that state can never be saved in the first place. (Within
 * this validator's OWN synthetic model, a single routine's own recurring daily occurrences can
 * never combine with EACH OTHER to exceed its own configured duration: every occurrence for a
 * given routine repeats on an exactly [MINUTES_PER_DAY]-minute cycle and every window this sweep
 * scans is exactly [MINUTES_PER_DAY] minutes wide, so a window's combined contribution from two
 * adjacent same-routine occurrences is invariant at exactly the configured duration, never more —
 * whatever a window gains from one occurrence's tail is always exactly offset by what it loses
 * from the next occurrence's head. That is a property of the MODEL, not a claim about real
 * elapsed time: real clocks are not always exactly [MINUTES_PER_DAY] minutes apart across a
 * daylight-saving transition, which is exactly why real-elapsed-time protection for THIS specific
 * scenario lives downstream in [se.blick.app.scheduling.RoutineActiveWindowWorker] instead —
 * see `HARD_FOREGROUND_RUNTIME_CAP_MINUTES`'s own doc.)
 *
 * Sums each qualifying occurrence's own configured duration rather than computing the union of
 * overlapping time ranges. Two routines active at the very same time still each run their OWN
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
        if (proposedStartTime == proposedEndTime) return RoutineDurationValidationResult.Valid
        val proposedMinutes = activeWindowDurationMinutes(proposedStartTime, proposedEndTime).toLong()
        // A non-positive duration is a different validation's concern (RoutineCreateUiState.
        // isTimeRangeValid) -- this validator only ever adds a non-negative contribution.
        if (proposedMinutes <= 0) return RoutineDurationValidationResult.Valid

        // The proposal's own occurrences are ALWAYS included -- its own standalone duration must
        // never exceed the cap regardless of enabled state (see this class's own doc).
        val occurrences = mutableListOf<Occurrence>()
        val proposedStartMinute = proposedStartTime.minuteOfDay()
        for (day in proposedActiveDays) {
            occurrences += Occurrence(day.cycleIndex() * MINUTES_PER_DAY + proposedStartMinute, proposedMinutes.toInt())
        }
        // Other routines only combine with the proposal if the proposal itself is enabled -- a
        // disabled proposal can never run alongside anything else right now (see this class's
        // own doc).
        if (proposedEnabled) {
            for (other in existingRoutines) {
                if (!other.enabled || other.id == proposedRoutineId) continue
                if (other.startTime == other.endTime) continue
                val otherMinutes = activeWindowDurationMinutes(other.startTime, other.endTime).toLong()
                if (otherMinutes <= 0) continue
                val otherStartMinute = other.startTime.minuteOfDay()
                for (day in other.activeDays) {
                    occurrences += Occurrence(day.cycleIndex() * MINUTES_PER_DAY + otherStartMinute, otherMinutes.toInt())
                }
            }
        }
        if (occurrences.isEmpty()) return RoutineDurationValidationResult.Valid

        // Difference-array sweep over three concatenated weekly cycles (previous/current/next),
        // with generous trailing padding for the last copy's occurrences -- so a rolling window
        // anchored anywhere in the "current" week correctly sees whichever adjacent-week
        // occurrences it should (e.g. a window spanning Sunday night into Monday morning), with
        // no risk of an occurrence's end index running past the array.
        val currentCycleStart = CYCLE_MINUTES
        val arraySize = CYCLE_MINUTES * 3 + MINUTES_PER_DAY + 1
        val delta = IntArray(arraySize)
        for (occurrence in occurrences) {
            for (cycleOffset in 0..2) {
                val start = occurrence.cycleStartMinute + cycleOffset * CYCLE_MINUTES
                val end = start + occurrence.durationMinutes
                delta[start] += 1
                delta[end] -= 1
            }
        }
        val occupancyPrefix = LongArray(arraySize + 1)
        var running = 0
        for (minute in 0 until arraySize) {
            running += delta[minute]
            occupancyPrefix[minute + 1] = occupancyPrefix[minute] + running
        }

        // Scanning every window start across one full week (the current cycle) is enough --
        // the pattern repeats every CYCLE_MINUTES, so any violation is found at its equivalent
        // position here regardless of which real week it would occur in.
        var worstTotal = 0L
        var worstWindowStart = currentCycleStart
        for (windowStart in currentCycleStart until currentCycleStart + CYCLE_MINUTES) {
            val total = occupancyPrefix[windowStart + MINUTES_PER_DAY] - occupancyPrefix[windowStart]
            if (total > worstTotal) {
                worstTotal = total
                worstWindowStart = windowStart
            }
        }

        if (worstTotal > MAX_DAILY_ACTIVE_MINUTES) {
            val dayIndex = (worstWindowStart - currentCycleStart) / MINUTES_PER_DAY
            return RoutineDurationValidationResult.ExceedsDailyLimit(DayOfWeek.of(dayIndex + 1), worstTotal.toInt())
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
     *
     * **Known limitation**: because this check has no access to the other stored routines, it
     * cannot catch a combined-total violation introduced by a FUTURE code change that saves
     * multiple simultaneously-enabled routines without going through [validate]'s full check
     * first (see [validate]'s own combined-total behavior). This is a deliberate, documented gap
     * rather than an oversight: today's single-routine limit (see `RoutineCreateViewModel`'s
     * `oneRoutineLimitReached`) makes it structurally unreachable, and closing it here would mean
     * making every [se.blick.app.scheduling.RoutineScheduler.scheduleActivation] caller suspend
     * (or otherwise threading a repository into a scheduler that today is fast, synchronous, and
     * callable from any thread) purely to defend against a scenario the current UI cannot
     * produce. Revisit this the moment routines are no longer limited to one at a time.
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
