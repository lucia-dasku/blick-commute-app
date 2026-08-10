package se.blick.app.domain.usecase

import se.blick.app.domain.model.CommuteRoutine
import java.time.DayOfWeek
import java.time.LocalTime

sealed interface RoutineOverlapValidationResult {
    data object Valid : RoutineOverlapValidationResult
    data class Overlaps(val routineId: String) : RoutineOverlapValidationResult
}

/** Compares recurring windows on a circular Monday-Sunday timeline. An end time equal to or
 * before its start belongs to the following day, so Sunday-night/Monday-morning and all other
 * midnight crossings are checked correctly. Touching endpoints are allowed. */
object RoutineScheduleOverlapValidator {
    private const val DAY = 24 * 60
    private const val WEEK = 7 * DAY
    private data class Interval(val start: Int, val end: Int)

    fun validate(proposed: CommuteRoutine, existing: List<CommuteRoutine>): RoutineOverlapValidationResult {
        if (!proposed.enabled) return RoutineOverlapValidationResult.Valid
        val proposedIntervals = intervals(proposed)
        existing.asSequence().filter { it.enabled && it.id != proposed.id }.forEach { other ->
            val otherIntervals = intervals(other)
            if (proposedIntervals.any { left -> otherIntervals.any { right -> overlapsCircular(left, right) } }) {
                return RoutineOverlapValidationResult.Overlaps(other.id)
            }
        }
        return RoutineOverlapValidationResult.Valid
    }

    /** Defensive coordinator for databases created by older/future code: returns a stable set
     * whose enabled schedules never overlap. Save-time validation should make this a no-op in
     * normal use, but workers never rely on that assumption for notification ownership. */
    fun nonOverlapping(routines: List<CommuteRoutine>): List<CommuteRoutine> {
        val accepted = mutableListOf<CommuteRoutine>()
        routines.sortedBy { it.id }.forEach { routine ->
            if (!routine.enabled || validate(routine, accepted) is RoutineOverlapValidationResult.Valid) accepted += routine
        }
        return accepted
    }

    private fun intervals(routine: CommuteRoutine): List<Interval> = routine.activeDays.map { day ->
        val start = day.index() * DAY + routine.startTime.minuteOfDay()
        var end = day.index() * DAY + routine.endTime.minuteOfDay()
        if (end <= start) end += DAY
        Interval(start, end)
    }

    private fun overlapsCircular(left: Interval, right: Interval): Boolean =
        listOf(-WEEK, 0, WEEK).any { shift -> left.start < right.end + shift && right.start + shift < left.end }

    private fun DayOfWeek.index() = value - 1
    private fun LocalTime.minuteOfDay() = toSecondOfDay() / 60
}
