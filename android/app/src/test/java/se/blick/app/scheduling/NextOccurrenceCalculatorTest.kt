package se.blick.app.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Plain JVM tests — no Android/Robolectric dependency, matching this calculator's own pure,
 * deterministic design (see its class doc). */
class NextOccurrenceCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")

    private fun routine(
        activeDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        startTime: LocalTime = LocalTime.of(7, 0),
        endTime: LocalTime = LocalTime.of(9, 0),
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

    @Test
    fun `no active days produces None`() {
        val result = NextOccurrenceCalculator.nextOccurrence(
            routine(activeDays = emptySet()),
            ZonedDateTime.of(2026, 7, 27, 6, 0, 0, 0, zone), // a Monday
        )
        assertEquals(NextOccurrence.None, result)
    }

    @Test
    fun `before today's window on an active day is Upcoming for today`() {
        val now = ZonedDateTime.of(2026, 7, 27, 6, 0, 0, 0, zone) // Monday 06:00, window 07:00-09:00
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now)

        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(ZonedDateTime.of(2026, 7, 27, 7, 0, 0, 0, zone), upcoming.windowStart)
        assertEquals(ZonedDateTime.of(2026, 7, 27, 9, 0, 0, 0, zone), upcoming.windowEnd)
    }

    @Test
    fun `inside today's window on an active day is ActiveNow`() {
        val now = ZonedDateTime.of(2026, 7, 27, 8, 0, 0, 0, zone) // Monday 08:00, inside 07:00-09:00
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now)

        val activeNow = result as NextOccurrence.ActiveNow
        assertEquals(ZonedDateTime.of(2026, 7, 27, 9, 0, 0, 0, zone), activeNow.windowEnd)
    }

    @Test
    fun `exactly at the start time is ActiveNow, not Upcoming`() {
        val now = ZonedDateTime.of(2026, 7, 27, 7, 0, 0, 0, zone)
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now)
        assertTrue(result is NextOccurrence.ActiveNow)
    }

    @Test
    fun `exactly at the end time is no longer ActiveNow`() {
        val now = ZonedDateTime.of(2026, 7, 27, 9, 0, 0, 0, zone)
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now)
        // 09:00 is the end instant itself -- the window has just closed, so this must roll
        // forward to the next active day (Wednesday), not report ActiveNow for a window that
        // has already ended.
        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(DayOfWeek.WEDNESDAY, upcoming.windowStart.dayOfWeek)
    }

    @Test
    fun `after today's window has ended rolls forward to the next active weekday`() {
        val now = ZonedDateTime.of(2026, 7, 27, 10, 0, 0, 0, zone) // Monday, after 09:00
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now)

        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(DayOfWeek.WEDNESDAY, upcoming.windowStart.dayOfWeek)
        assertEquals(29, upcoming.windowStart.dayOfMonth) // 2026-07-29 is the Wednesday
    }

    @Test
    fun `skips inactive weekdays entirely`() {
        val fridayOnly = routine(activeDays = setOf(DayOfWeek.FRIDAY))
        val now = ZonedDateTime.of(2026, 7, 27, 6, 0, 0, 0, zone) // Monday
        val result = NextOccurrenceCalculator.nextOccurrence(fridayOnly, now)

        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(DayOfWeek.FRIDAY, upcoming.windowStart.dayOfWeek)
        assertEquals(31, upcoming.windowStart.dayOfMonth) // 2026-07-31 is the Friday
    }

    @Test
    fun `wraps around into the following week when the only active day already passed this week`() {
        val mondayOnly = routine(activeDays = setOf(DayOfWeek.MONDAY))
        val now = ZonedDateTime.of(2026, 7, 27, 10, 0, 0, 0, zone) // Monday, after today's window
        val result = NextOccurrenceCalculator.nextOccurrence(mondayOnly, now)

        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(DayOfWeek.MONDAY, upcoming.windowStart.dayOfWeek)
        assertEquals(3, upcoming.windowStart.dayOfMonth) // 2026-08-03, the following Monday
    }

    @Test
    fun `an excluded date (paused for today) is skipped even though it is an active weekday`() {
        val now = ZonedDateTime.of(2026, 7, 27, 6, 0, 0, 0, zone) // Monday, before today's window
        val result = NextOccurrenceCalculator.nextOccurrence(routine(), now, excludedDate = now.toLocalDate())

        val upcoming = result as NextOccurrence.Upcoming
        assertEquals(DayOfWeek.WEDNESDAY, upcoming.windowStart.dayOfWeek)
    }

    @Test
    fun `every calendar day active still resolves ActiveNow correctly`() {
        val everyDay = routine(activeDays = DayOfWeek.entries.toSet())
        val now = ZonedDateTime.of(2026, 7, 27, 8, 0, 0, 0, zone)
        val result = NextOccurrenceCalculator.nextOccurrence(everyDay, now)
        assertTrue(result is NextOccurrence.ActiveNow)
    }

    // ---- DST ----

    @Test
    fun `a spring-forward gap resolves to a valid instant rather than throwing`() {
        // Europe/Stockholm springs forward at 02:00 -> 03:00 on the last Sunday of March; a
        // routine whose start time falls inside that gap must still resolve to SOME concrete
        // instant (the JDK's own gap-resolution behaviour), not throw.
        val dstGapRoutine = routine(activeDays = setOf(DayOfWeek.SUNDAY), startTime = LocalTime.of(2, 30), endTime = LocalTime.of(4, 0))
        val now = ZonedDateTime.of(2027, 3, 28, 0, 0, 0, 0, zone) // the Sunday of the 2027 spring-forward
        val result = NextOccurrenceCalculator.nextOccurrence(dstGapRoutine, now)

        assertTrue(result is NextOccurrence.Upcoming || result is NextOccurrence.ActiveNow)
    }

    @Test
    fun `a fall-back overlap still produces a single well-defined occurrence`() {
        // Europe/Stockholm falls back at 03:00 -> 02:00 on the last Sunday of October; 02:30
        // occurs twice that day. ZonedDateTime.of resolves the ambiguity to one specific
        // offset deterministically rather than this calculator needing to disambiguate it.
        val dstOverlapRoutine = routine(activeDays = setOf(DayOfWeek.SUNDAY), startTime = LocalTime.of(2, 30), endTime = LocalTime.of(4, 0))
        val now = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, zone) // the Sunday of the 2026 fall-back
        val result = NextOccurrenceCalculator.nextOccurrence(dstOverlapRoutine, now)

        assertTrue(result is NextOccurrence.Upcoming)
    }
}
