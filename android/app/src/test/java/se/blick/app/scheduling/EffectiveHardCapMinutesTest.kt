package se.blick.app.scheduling

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct, pure-function tests of [effectiveHardCapMinutes] -- no worker, no Robolectric, no
 * fakes. Uses real [ZonedDateTime] arithmetic against the actual 2027 Europe/Stockholm
 * daylight-saving transitions (matching [NextOccurrenceCalculatorTest]'s own DST tests, which use
 * the same zone and dates), never a hardcoded date check or `isDaylightSavings`. See
 * [RoutineActiveWindowWorkerTest] for the full worker-level integration tests proving the
 * computed value is actually persisted and enforced end to end.
 */
class EffectiveHardCapMinutesTest {

    private val stockholm: ZoneId = ZoneId.of("Europe/Stockholm")

    private fun routine(
        activeDays: Set<DayOfWeek>,
        startTime: LocalTime,
        endTime: LocalTime,
    ) = CommuteRoutine(
        id = "r1",
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = activeDays,
        startTime = startTime,
        endTime = endTime,
    )

    // ---- Spring forward: Saturday 27 -> Sunday 28 March 2027 ----

    @Test
    fun `the same clock time on consecutive days is only 23 real hours apart across the spring transition`() {
        val saturday0700 = ZonedDateTime.of(2027, 3, 27, 7, 0, 0, 0, stockholm)
        val sunday0700 = ZonedDateTime.of(2027, 3, 28, 7, 0, 0, 0, stockholm)

        // This is exactly the gap RoutineDurationValidator's own synthetic model assumes is
        // always exactly 24h (1440 minutes) -- real Stockholm clocks are not, which is exactly
        // what effectiveHardCapMinutes below detects and compensates for.
        assertEquals(Duration.ofHours(23), Duration.between(saturday0700, sunday0700))
    }

    @Test
    fun `a 5-hour routine's Saturday occurrence, immediately before the spring gap, is reduced to 270 minutes`() {
        val dailyFiveHourRoutine = routine(
            activeDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(12, 0), // 5h -- exactly MAX_DAILY_ACTIVE_MINUTES
        )
        val saturdayStart = ZonedDateTime.of(2027, 3, 27, 7, 0, 0, 0, stockholm)
        val saturdayEnd = ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, stockholm)

        val capMinutes = effectiveHardCapMinutes(dailyFiveHourRoutine, saturdayStart, saturdayEnd)

        // 330 (the normal cap) - (24h expected - 23h real) = 330 - 60 = 270.
        assertEquals(270L, capMinutes)
    }

    @Test
    fun `combining Saturday's reduced 270-minute cap with Sunday's own runtime stays at exactly 330 real minutes`() {
        // Direct arithmetic proof that the 270-minute reduction computed above is EXACTLY what's
        // needed, not merely a safe overestimate -- the same worst-case rolling-24h window
        // reasoning HARD_FOREGROUND_RUNTIME_CAP_MINUTES's own doc describes.
        val saturdayStart = ZonedDateTime.of(2027, 3, 27, 7, 0, 0, 0, stockholm)
        val saturdayEnd = saturdayStart.plusMinutes(270) // capped, per the test above
        val sundayStart = ZonedDateTime.of(2027, 3, 28, 7, 0, 0, 0, stockholm)
        // Modeled running for 270 minutes here too -- NOT a claim that Sunday's own cap is also
        // reduced (see the dedicated "not permanently restricted" test below, which asserts the
        // real function output and confirms Sunday's own cap is the full 330). This is simply
        // enough runtime to fill the window's remaining capacity for this hand-worked arithmetic
        // proof; any value from 60 minutes up would demonstrate the same combined total.
        val sundayEnd = sundayStart.plusMinutes(270)

        val windowStart = saturdayStart
        val windowEnd = windowStart.plus(Duration.ofHours(24)) // a genuine real-elapsed 24 hours

        assertTrue("Saturday's capped occurrence must be fully inside the window", !saturdayEnd.isAfter(windowEnd))
        val overlapStart = if (sundayStart.isAfter(windowStart)) sundayStart else windowStart
        val overlapEnd = if (sundayEnd.isBefore(windowEnd)) sundayEnd else windowEnd
        val sundayMinutesInWindow = Duration.between(overlapStart, overlapEnd).toMinutes()

        val combinedRealMinutes = 270L + sundayMinutesInWindow
        assertEquals(330L, combinedRealMinutes)
        assertTrue(
            "must stay at or under HARD_FOREGROUND_RUNTIME_CAP_MINUTES",
            combinedRealMinutes <= HARD_FOREGROUND_RUNTIME_CAP_MINUTES,
        )
    }

    @Test
    fun `Sunday's own occurrence is not permanently restricted to 270 minutes`() {
        // Sunday's own NEXT occurrence (the following Saturday, a normal week later) sits across
        // an entirely ordinary, non-DST gap -- Sunday's cap must be the full, unreduced 330.
        val dailyFiveHourRoutine = routine(
            activeDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(12, 0),
        )
        val sundayStart = ZonedDateTime.of(2027, 3, 28, 7, 0, 0, 0, stockholm)
        val sundayEnd = ZonedDateTime.of(2027, 3, 28, 12, 0, 0, 0, stockholm)

        val capMinutes = effectiveHardCapMinutes(dailyFiveHourRoutine, sundayStart, sundayEnd)

        assertEquals(HARD_FOREGROUND_RUNTIME_CAP_MINUTES, capMinutes)
    }

    // ---- Fall back: Sunday 31 October 2027 -- confirms NO reduction is ever applied here ----

    @Test
    fun `the autumn transition never reduces the cap -- the real gap only ever lengthens`() {
        // Sunday 00:00 to Monday 00:00 crosses the repeated hour, so the real gap is LONGER
        // (25h) than the local 24h the naive model assumes -- the exact opposite direction from
        // the spring case, so effectiveHardCapMinutes must never reduce anything here. Autumn's
        // own danger (a single occurrence spanning the repeated hour) is instead handled entirely
        // by the existing flat HARD_FOREGROUND_RUNTIME_CAP_MINUTES cap -- see that constant's own
        // doc, and RoutineActiveWindowWorkerTest for the integration proof.
        val dailyFiveHourRoutine = routine(
            activeDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
            startTime = LocalTime.of(0, 0),
            endTime = LocalTime.of(5, 0), // 5h
        )
        val sundayStart = ZonedDateTime.of(2027, 10, 31, 0, 0, 0, 0, stockholm)
        val sundayEnd = ZonedDateTime.of(2027, 10, 31, 5, 0, 0, 0, stockholm)
        val mondayStart = ZonedDateTime.of(2027, 11, 1, 0, 0, 0, 0, stockholm)

        assertEquals(Duration.ofHours(25), Duration.between(sundayStart, mondayStart))

        val capMinutes = effectiveHardCapMinutes(dailyFiveHourRoutine, sundayStart, sundayEnd)
        assertEquals(HARD_FOREGROUND_RUNTIME_CAP_MINUTES, capMinutes)
    }

    // ---- Non-DST sanity: consecutive-day occurrences, no transition nearby ----

    @Test
    fun `a routine active on consecutive days with no DST transition nearby is not reduced`() {
        // CommuteRoutine has a single, fixed startTime/endTime applied to EVERY active day --
        // there is no way to configure a different clock time per day. The closest two of a
        // routine's own occurrences can ever be, in LOCAL terms, is exactly 1440 minutes apart
        // (consecutive active days, same clock time each day -- here, Monday 23:00 and Tuesday
        // 23:00). What CAN differ from that 1440 is only the REAL elapsed time between those two
        // identical local starts, and only because of a DST transition sitting between them (see
        // the Stockholm Saturday-to-Sunday tests above for that case). With no such transition
        // anywhere near this ordinary June week, the real gap tracks the local gap exactly, so
        // this must not be reduced.
        val consecutiveDayRoutine = routine(
            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            startTime = LocalTime.of(23, 0),
            endTime = LocalTime.of(23, 30),
        )
        val mondayStart = ZonedDateTime.of(2027, 6, 7, 23, 0, 0, 0, stockholm) // an ordinary June Monday
        val mondayEnd = ZonedDateTime.of(2027, 6, 7, 23, 30, 0, 0, stockholm)
        val tuesdayStart = ZonedDateTime.of(2027, 6, 8, 23, 0, 0, 0, stockholm)

        // Confirms the premise: real and local gaps are identical here (no DST anywhere nearby).
        assertEquals(Duration.ofHours(24), Duration.between(mondayStart, tuesdayStart))

        val capMinutes = effectiveHardCapMinutes(consecutiveDayRoutine, mondayStart, mondayEnd)

        assertEquals(HARD_FOREGROUND_RUNTIME_CAP_MINUTES, capMinutes)
    }

    // ---- A multi-day gap that merely happens to CROSS a DST transition must not be reduced --
    // only a gap that is itself under 24 real hours is ever in danger ----

    @Test
    fun `a Friday-to-Monday routine spanning the spring transition is NOT reduced, even though local and real gaps differ`() {
        // A Mon/Wed/Fri-style routine whose Friday occurrence is followed by Monday's, three
        // calendar days later. The 2027 spring transition (Sat 27 - Sun 28 March) falls inside
        // that span, so the real gap (71h) is 60 minutes SHORTER than the naive local gap (72h)
        // -- exactly the same kind of local/real divergence the Stockholm Saturday-to-Sunday case
        // above has. The difference is entirely in how FAR APART the two occurrences already are:
        // 71 real hours is nowhere near a single rolling 24-hour window, so unlike the
        // Saturday-to-Sunday case, this must NOT be reduced.
        val fridayMondayRoutine = routine(
            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(12, 0), // 5h
        )
        val fridayStart = ZonedDateTime.of(2027, 3, 26, 7, 0, 0, 0, stockholm)
        val fridayEnd = ZonedDateTime.of(2027, 3, 26, 12, 0, 0, 0, stockholm)
        val mondayStart = ZonedDateTime.of(2027, 3, 29, 7, 0, 0, 0, stockholm)

        // Confirms the premise: real and local gaps genuinely differ here, exactly as they do in
        // the Stockholm Saturday-to-Sunday case -- so a fix that merely compared local vs. real
        // gaps, without also checking how large the real gap itself is, would still wrongly
        // reduce this occurrence's cap.
        assertEquals(71 * 60L, Duration.between(fridayStart, mondayStart).toMinutes())
        assertEquals(72 * 60L, Duration.between(fridayStart.toLocalDateTime(), mondayStart.toLocalDateTime()).toMinutes())

        val capMinutes = effectiveHardCapMinutes(fridayMondayRoutine, fridayStart, fridayEnd)

        assertEquals(HARD_FOREGROUND_RUNTIME_CAP_MINUTES, capMinutes)
    }

    // ---- No next occurrence to compare against ----

    @Test
    fun `a routine active on only one day still gets the full cap even though its own next occurrence is a week away`() {
        val onceAWeekRoutine = routine(
            activeDays = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(12, 0),
        )
        val mondayStart = ZonedDateTime.of(2027, 6, 7, 7, 0, 0, 0, stockholm)
        val mondayEnd = ZonedDateTime.of(2027, 6, 7, 12, 0, 0, 0, stockholm)

        val capMinutes = effectiveHardCapMinutes(onceAWeekRoutine, mondayStart, mondayEnd)

        assertEquals(HARD_FOREGROUND_RUNTIME_CAP_MINUTES, capMinutes)
    }
}
