package se.blick.app.ui.screens.routinedetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * These are plain pure-function tests (no Compose, no Android framework) for the small
 * formatting helpers used by [RoutineDetailsScreen] — kept separate from
 * [se.blick.app.domain.usecase.LiveDeparturesProcessorTest], which already comprehensively
 * covers the engine's own filtering/sorting/countdown logic.
 */
class RoutineDetailsFormattingTest {

    private val locale = Locale.US
    private val everyDayLabel = "Every day"
    private val weekdaysLabel = "Weekdays"

    private fun format(days: Set<DayOfWeek>) = formatActiveDays(days, locale, everyDayLabel, weekdaysLabel)

    @Test
    fun `formatActiveDays renders days in calendar order regardless of set iteration order`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)

        assertEquals("Mon, Wed, Fri", format(days))
    }

    @Test
    fun `formatActiveDays handles a single day`() {
        assertEquals("Mon", format(setOf(DayOfWeek.MONDAY)))
    }

    @Test
    fun `formatActiveDays handles an empty set`() {
        assertEquals("", format(emptySet()))
    }

    @Test
    fun `formatActiveDays renders all seven days as the every-day label`() {
        assertEquals(everyDayLabel, format(DayOfWeek.values().toSet()))
    }

    @Test
    fun `formatActiveDays renders exactly Monday through Friday as the weekdays label`() {
        val weekdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )

        assertEquals(weekdaysLabel, format(weekdays))
    }

    @Test
    fun `formatActiveDays does not use the weekdays label when a weekend day is also selected`() {
        // All five weekdays plus Saturday -- six days, not the exact Mon-Fri set, so this must
        // still fall through to the ordinary day list rather than being mistaken for "Weekdays".
        val weekdaysPlusSaturday = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
        )

        assertEquals("Mon, Tue, Wed, Thu, Fri, Sat", format(weekdaysPlusSaturday))
    }

    @Test
    fun `formatActiveDays does not use the weekdays label for fewer than five weekdays`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        assertEquals("Mon, Wed, Fri", format(days))
    }

    @Test
    fun `formatActiveDays renders a weekend-only selection as the ordinary day list, not a special label`() {
        assertEquals("Sat, Sun", format(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
    }

    @Test
    fun `formatTimeRange renders both the start and end time`() {
        val range = formatTimeRange(LocalTime.of(7, 0), LocalTime.of(9, 0), locale)

        assertTrue(range.contains("7:00"))
        assertTrue(range.contains("9:00"))
    }

    @Test
    fun `formatDepartureTime renders the instant in the given zone`() {
        val instant = Instant.parse("2026-07-28T08:15:00Z")

        val formatted = formatDepartureTime(instant, locale, ZoneOffset.UTC)

        assertTrue(formatted.contains("8:15"))
    }

    @Test
    fun `formatDepartureTime shifts with the supplied zone`() {
        val instant = Instant.parse("2026-07-28T08:15:00Z")

        val utc = formatDepartureTime(instant, locale, ZoneOffset.UTC)
        val plusTwo = formatDepartureTime(instant, locale, ZoneOffset.ofHours(2))

        assertTrue(utc.contains("8:15"))
        assertTrue(plusTwo.contains("10:15"))
    }
}
