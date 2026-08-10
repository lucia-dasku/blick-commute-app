package se.blick.app.scheduling

import se.blick.app.domain.model.CommuteRoutine
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The result of asking "when should this routine's active window next run?" relative to a
 * supplied instant. Deliberately distinguishes "the window is already open right now" from
 * "the window is somewhere in the future" — [WorkManagerRoutineScheduler] needs the former to
 * decide whether a late-starting worker should still run this occurrence or skip it (see its
 * own doc comment), and [RoutineActiveWindowWorker] needs [ActiveNow.windowEnd] to know when
 * to stop its 30-second loop.
 */
sealed interface NextOccurrence {
    /** `now` falls inside today's (or [reference]'s) configured window: `startTime` has
     * already passed but `endTime` has not. */
    data class ActiveNow(val windowEnd: ZonedDateTime) : NextOccurrence

    /** The next eligible window has not started yet. */
    data class Upcoming(val windowStart: ZonedDateTime, val windowEnd: ZonedDateTime) : NextOccurrence

    /** [CommuteRoutine.activeDays] is empty, or every candidate day in the search horizon was
     * excluded (see [NextOccurrenceCalculator.nextOccurrence]'s `excludedDate` parameter) —
     * there is nothing to schedule. */
    data object None : NextOccurrence
}

/**
 * Pure, deterministic "what's the next active window" calculation — no I/O, no Android
 * dependency, no WorkManager — so it is unit-testable with plain JVM tests against any
 * [ZonedDateTime] (see product doc's Android-scheduling-limitations section: this only ever
 * produces a best-effort target; the actual worker start is still subject to ordinary
 * WorkManager/JobScheduler deferral, never an exact-to-the-second guarantee — no
 * `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` is used anywhere in this codebase).
 *
 * Windows may cross midnight. The selected weekday is the start day, and an end time equal to
 * or before the start is resolved on the following date.
 *
 * [ZonedDateTime] (not [java.time.Instant]) is used throughout deliberately: routines are
 * defined in the device's local wall-clock time (see [CommuteRoutine.startTime]/`endTime`,
 * both plain [java.time.LocalTime] with no zone), and re-deriving a wall-clock start/end for
 * each candidate date via `ZonedDateTime.of(date, time, zone)` is exactly how the JDK resolves
 * DST gaps (an invalid, skipped local time is pushed forward past the gap) and overlaps (an
 * ambiguous local time resolves to the earlier of the two real instants) — both handled by the
 * platform, not reimplemented here.
 */
object NextOccurrenceCalculator {

    /** How many calendar days ahead to search for an eligible [CommuteRoutine.activeDays] day
     * before concluding there isn't one — 8 covers "today plus a full week" so every weekday
     * combination (including a single active day) is always found within the horizon. */
    private const val SEARCH_HORIZON_DAYS = 8L

    /**
     * @param excludedDate a single calendar date to treat as ineligible even if its weekday is
     * in [CommuteRoutine.activeDays] — used for "paused for today" (see
     * [CommuteRoutine.pausedDate]), so a routine paused for today is correctly scheduled for
     * its next eligible day instead of today, without [CommuteRoutine.activeDays] itself
     * needing to change.
     */
    fun nextOccurrence(
        routine: CommuteRoutine,
        now: ZonedDateTime,
        excludedDate: LocalDate? = null,
    ): NextOccurrence {
        if (routine.activeDays.isEmpty()) return NextOccurrence.None

        for (dayOffset in -1 until SEARCH_HORIZON_DAYS) {
            val candidateDate = now.toLocalDate().plusDays(dayOffset)
            if (candidateDate.dayOfWeek !in routine.activeDays) continue

            val candidateStart = ZonedDateTime.of(candidateDate, routine.startTime, now.zone)
            val endDate = if (routine.endTime.isAfter(routine.startTime)) candidateDate else candidateDate.plusDays(1)
            val candidateEnd = ZonedDateTime.of(endDate, routine.endTime, now.zone)
            if (candidateDate == excludedDate || endDate == excludedDate) continue

            if (now.isBefore(candidateEnd) && !now.isBefore(candidateStart)) {
                return NextOccurrence.ActiveNow(candidateEnd)
            }
            if (candidateStart.isAfter(now)) {
                return NextOccurrence.Upcoming(candidateStart, candidateEnd)
            }
            // Otherwise this candidate day's window has already fully elapsed (dayOffset ==
            // 0 and now is past candidateEnd) -- keep searching forward.
        }
        return NextOccurrence.None
    }
}
