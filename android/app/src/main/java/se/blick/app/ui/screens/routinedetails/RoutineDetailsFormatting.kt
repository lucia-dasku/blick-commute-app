package se.blick.app.ui.screens.routinedetails

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
 * during routine creation, since [days] is an unordered [Set].
 */
fun formatActiveDays(days: Set<DayOfWeek>, locale: Locale): String =
    DayOfWeek.values()
        .filter { it in days }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }

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
