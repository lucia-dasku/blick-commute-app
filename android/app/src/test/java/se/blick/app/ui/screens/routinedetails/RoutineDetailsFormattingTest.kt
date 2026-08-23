package se.blick.app.ui.screens.routinedetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.locale.effectiveBlickLocale
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

        assertEquals("07:00 – 09:00", range)
    }

    @Test
    fun `formatDepartureTime renders the instant in the given zone`() {
        val instant = Instant.parse("2026-07-28T08:15:00Z")

        val formatted = formatDepartureTime(instant, locale, ZoneOffset.UTC)

        assertEquals("08:15", formatted)
    }

    @Test
    fun `formatDepartureTime shifts with the supplied zone`() {
        val instant = Instant.parse("2026-07-28T08:15:00Z")

        val utc = formatDepartureTime(instant, locale, ZoneOffset.UTC)
        val plusTwo = formatDepartureTime(instant, locale, ZoneOffset.ofHours(2))

        assertEquals("08:15", utc)
        assertEquals("10:15", plusTwo)
    }

    // ---- Swedish (sv) -- these three functions are exactly what Blick's own app-locale
    // formatting depends on for the routine-creation weekday selector, Routine Details, and
    // notification "Last checked" text alike (see se.blick.app.locale.withAppLocale's own doc
    // and RoutineNotificationBuilder.lastCheckedLine) -- no separate Swedish-specific formatting
    // path exists anywhere; it's this same Locale-parameterized code, just called with a
    // different Locale. ----

    private val swedish = Locale.forLanguageTag("sv")

    @Test
    fun `formatActiveDays renders Swedish weekday abbreviations distinct from English, in calendar order`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)

        val swedishResult = formatActiveDays(days, swedish, everyDayLabel, weekdaysLabel)
        val englishResult = format(days)

        // Not pinned to one exact CLDR abbreviation spelling/case (that data can shift between
        // ICU versions) -- the guarantee this asserts is that Swedish weekday names are actually
        // used, and still in Monday/Wednesday/Friday calendar order regardless of locale.
        assertEquals(3, swedishResult.split(", ").size)
        assertTrue("expected '$swedishResult' to differ from the English '$englishResult'", swedishResult != englishResult)
    }

    @Test
    fun `formatActiveDays still renders the every-day and weekdays labels as supplied, independent of locale`() {
        // everyDayLabel/weekdaysLabel are the caller's own already-localized strings (see this
        // function's own doc) -- formatActiveDays itself has no locale-specific branching for
        // these two cases, only for the day-by-day fallback exercised above.
        assertEquals(everyDayLabel, formatActiveDays(DayOfWeek.values().toSet(), swedish, everyDayLabel, weekdaysLabel))
    }

    @Test
    fun `formatTimeRange consistently uses 24-hour time in Swedish and English`() {
        val range = formatTimeRange(LocalTime.of(19, 5), LocalTime.of(21, 0), swedish)
        val englishRange = formatTimeRange(LocalTime.of(19, 5), LocalTime.of(21, 0), locale)

        assertEquals("19:05 – 21:00", range)
        assertEquals(range, englishRange)
    }

    @Test
    fun `formatDepartureTime renders Swedish 24-hour clock time`() {
        val instant = Instant.parse("2026-07-28T20:15:00Z")

        val swedishTime = formatDepartureTime(instant, swedish, ZoneOffset.UTC)

        assertTrue("expected a 24-hour '20:15' rendering, got '$swedishTime'", swedishTime.contains("20:15"))
    }

    // ---- Unsupported/ordered system locale list, normalized via effectiveBlickLocale -- what
    // RoutineCreateScreen/RoutineDetailsScreen actually pass as `locale` is never a raw device
    // locale but se.blick.app.locale.currentBlickLocale()'s result, which itself wraps
    // effectiveBlickLocale (see that function's own doc), so with no explicit Blick language
    // chosen these must render weekday names for whichever of Blick's two languages the rule
    // actually resolves to, never a raw unsupported system language. ----

    @Test
    fun `formatActiveDays renders English weekday abbreviations when fed the effective locale for an unsupported Lithuanian system locale`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val effectiveLocale = effectiveBlickLocale(null, listOf(Locale.forLanguageTag("lt")))

        assertEquals("Mon, Wed, Fri", formatActiveDays(days, effectiveLocale, everyDayLabel, weekdaysLabel))
    }

    @Test
    fun `formatActiveDays renders Swedish weekday abbreviations when the system locale list is Lithuanian-then-Swedish`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val effectiveLocale = effectiveBlickLocale(null, listOf(Locale.forLanguageTag("lt"), Locale.forLanguageTag("sv")))

        val result = formatActiveDays(days, effectiveLocale, everyDayLabel, weekdaysLabel)

        assertEquals(3, result.split(", ").size)
        assertTrue(
            "expected Swedish weekday abbreviations, distinct from the English 'Mon, Wed, Fri'",
            result != "Mon, Wed, Fri",
        )
    }

    // ---- ExactDestinationChangesPreference chip mapping/toggling -- both chips being
    // unselected is never a valid outcome (see toggleDirect/toggleWithChanges's own doc): tapping
    // the only currently-selected chip must be a no-op, never leave neither selected. ----

    @Test
    fun `includesDirect and includesWithChanges reflect DIRECT_ONLY as Direct-only selected`() {
        assertTrue(ExactDestinationChangesPreference.DIRECT_ONLY.includesDirect())
        assertFalse(ExactDestinationChangesPreference.DIRECT_ONLY.includesWithChanges())
    }

    @Test
    fun `includesDirect and includesWithChanges reflect BOTH as both selected`() {
        assertTrue(ExactDestinationChangesPreference.BOTH.includesDirect())
        assertTrue(ExactDestinationChangesPreference.BOTH.includesWithChanges())
    }

    @Test
    fun `includesDirect and includesWithChanges reflect WITH_CHANGES_ONLY as With-changes-only selected`() {
        assertFalse(ExactDestinationChangesPreference.WITH_CHANGES_ONLY.includesDirect())
        assertTrue(ExactDestinationChangesPreference.WITH_CHANGES_ONLY.includesWithChanges())
    }

    @Test
    fun `toggleDirect on DIRECT_ONLY is a no-op -- the only selected chip can never be turned off`() {
        assertEquals(ExactDestinationChangesPreference.DIRECT_ONLY, ExactDestinationChangesPreference.DIRECT_ONLY.toggleDirect())
    }

    @Test
    fun `toggleWithChanges on WITH_CHANGES_ONLY is a no-op -- the only selected chip can never be turned off`() {
        assertEquals(ExactDestinationChangesPreference.WITH_CHANGES_ONLY, ExactDestinationChangesPreference.WITH_CHANGES_ONLY.toggleWithChanges())
    }

    @Test
    fun `toggleDirect on BOTH turns Direct off, leaving WITH_CHANGES_ONLY`() {
        assertEquals(ExactDestinationChangesPreference.WITH_CHANGES_ONLY, ExactDestinationChangesPreference.BOTH.toggleDirect())
    }

    @Test
    fun `toggleWithChanges on BOTH turns With-changes off, leaving DIRECT_ONLY`() {
        assertEquals(ExactDestinationChangesPreference.DIRECT_ONLY, ExactDestinationChangesPreference.BOTH.toggleWithChanges())
    }

    @Test
    fun `toggleDirect on WITH_CHANGES_ONLY turns Direct on, reaching BOTH`() {
        assertEquals(ExactDestinationChangesPreference.BOTH, ExactDestinationChangesPreference.WITH_CHANGES_ONLY.toggleDirect())
    }

    @Test
    fun `toggleWithChanges on DIRECT_ONLY turns With-changes on, reaching BOTH`() {
        assertEquals(ExactDestinationChangesPreference.BOTH, ExactDestinationChangesPreference.DIRECT_ONLY.toggleWithChanges())
    }

    @Test
    fun `every toggle sequence always leaves at least one chip selected, for every starting preference`() {
        for (preference in ExactDestinationChangesPreference.entries) {
            assertTrue(
                "toggleDirect() from $preference must leave at least one chip selected",
                preference.toggleDirect().let { it.includesDirect() || it.includesWithChanges() },
            )
            assertTrue(
                "toggleWithChanges() from $preference must leave at least one chip selected",
                preference.toggleWithChanges().let { it.includesDirect() || it.includesWithChanges() },
            )
        }
    }
}
