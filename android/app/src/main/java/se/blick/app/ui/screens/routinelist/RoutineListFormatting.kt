package se.blick.app.ui.screens.routinelist

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.ui.screens.routinedetails.formatActiveDays
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ROUTINE_CARD_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatRoutineCardSchedule(
    routine: CommuteRoutine,
    locale: Locale,
    everyDayLabel: String,
    weekdaysLabel: String,
): String {
    val days = formatActiveDays(routine.activeDays, locale, everyDayLabel, weekdaysLabel)
    val start = routine.startTime.format(ROUTINE_CARD_TIME_FORMATTER)
    val end = routine.endTime.format(ROUTINE_CARD_TIME_FORMATTER)
    return "$days · $start–$end"
}
