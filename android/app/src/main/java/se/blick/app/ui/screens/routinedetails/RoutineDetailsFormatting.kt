package se.blick.app.ui.screens.routinedetails

import se.blick.app.domain.model.ExactDestinationChangesPreference
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Small, pure (no Compose, no Android framework) formatting helpers for the routine
 * details screen. Kept separate from the Composables so they're directly unit-testable
 * with a fixed [Locale]/[ZoneId] rather than only exercisable through UI tests.
 */

/**
 * Renders [days] in calendar order (Monday first) using locale-aware short weekday
 * names, e.g. "Mon, Wed, Fri" — regardless of the order they were originally toggled in
 * during routine creation, since [days] is an unordered [Set]. Two common combinations get a
 * cleaner label instead of the day-by-day list: all seven days renders as [everyDayLabel], and
 * exactly Monday through Friday — no fewer, no more, never a weekend day mixed in — renders as
 * [weekdaysLabel]. Every other combination (including "weekdays plus one weekend day", or any
 * other subset) still falls through to the comma-separated list. Display-only: [days] itself is
 * never altered by which of the three renderings a given set happens to produce, so this has no
 * effect on how active days are stored, selected, or scheduled.
 */
fun formatActiveDays(days: Set<DayOfWeek>, locale: Locale, everyDayLabel: String, weekdaysLabel: String): String = when {
    days.size == DayOfWeek.values().size -> everyDayLabel
    days == WEEKDAYS -> weekdaysLabel
    else -> DayOfWeek.values().filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

private val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

/** Locale-aware short start–end time range, e.g. "7:00 AM – 9:00 AM". */
fun formatTimeRange(start: LocalTime, end: LocalTime, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return "${start.format(formatter)} – ${end.format(formatter)}"
}

/**
 * Locale-aware clock time for a departure's effective instant, rendered in [zone]
 * (defaults to the device's current time zone) — matches how a person reads a physical
 * departure board, rather than showing a raw UTC instant.
 */
fun formatDepartureTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    return formatter.format(instant)
}

/**
 * [ExactDestinationChangesPreference] as two independently-toggleable chip states — the Direct /
 * With changes [JourneyFilterRow] shows — rather than a bare three-way enum: [includesDirect]
 * mirrors the "Direct" chip's own selected state, [includesWithChanges] the "With changes" chip's.
 * [WITH_CHANGES_ONLY][ExactDestinationChangesPreference.WITH_CHANGES_ONLY] is the only value where
 * Direct reads unselected; [DIRECT_ONLY][ExactDestinationChangesPreference.DIRECT_ONLY] the only
 * one where With changes does — [BOTH][ExactDestinationChangesPreference.BOTH] selects both.
 */
fun ExactDestinationChangesPreference.includesDirect(): Boolean = this != ExactDestinationChangesPreference.WITH_CHANGES_ONLY

fun ExactDestinationChangesPreference.includesWithChanges(): Boolean = this != ExactDestinationChangesPreference.DIRECT_ONLY

/**
 * The preference that results from tapping the "Direct" chip while [this] is the currently
 * persisted preference — a no-op (returns [this] unchanged) when Direct is the ONLY currently-
 * selected option ([ExactDestinationChangesPreference.DIRECT_ONLY]), since both chips being
 * unselected is never a valid state: an intentionally unreachable "nothing selected" preference
 * would just leave no journeys eligible for no discoverable reason, a dead end no tap could
 * recover from except turning the OTHER chip on first. See [toggleWithChanges] for the same rule
 * on the other chip.
 */
fun ExactDestinationChangesPreference.toggleDirect(): ExactDestinationChangesPreference = when (this) {
    ExactDestinationChangesPreference.DIRECT_ONLY -> ExactDestinationChangesPreference.DIRECT_ONLY
    ExactDestinationChangesPreference.BOTH -> ExactDestinationChangesPreference.WITH_CHANGES_ONLY
    ExactDestinationChangesPreference.WITH_CHANGES_ONLY -> ExactDestinationChangesPreference.BOTH
}

/** The preference that results from tapping the "With changes" chip — see [toggleDirect]'s own
 * doc for the identical both-chips-off guard, mirrored here for [ExactDestinationChangesPreference.WITH_CHANGES_ONLY]. */
fun ExactDestinationChangesPreference.toggleWithChanges(): ExactDestinationChangesPreference = when (this) {
    ExactDestinationChangesPreference.DIRECT_ONLY -> ExactDestinationChangesPreference.BOTH
    ExactDestinationChangesPreference.BOTH -> ExactDestinationChangesPreference.DIRECT_ONLY
    ExactDestinationChangesPreference.WITH_CHANGES_ONLY -> ExactDestinationChangesPreference.WITH_CHANGES_ONLY
}
