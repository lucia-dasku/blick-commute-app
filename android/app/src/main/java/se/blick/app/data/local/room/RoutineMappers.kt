package se.blick.app.data.local.room

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toTransportMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private fun Set<DayOfWeek>.toMask(): Int =
    fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

private fun Int.toDayOfWeekSet(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { day -> (this shr (day.value - 1)) and 1 == 1 }.toSet()

fun RoutineEntity.toDomain(): CommuteRoutine = CommuteRoutine(
    id = id,
    name = name,
    siteId = siteId,
    siteName = siteName,
    transportMode = transportMode.toTransportMode(),
    lineId = lineId,
    lineDesignation = lineDesignation,
    directionCode = directionCode,
    destinationLabel = destinationLabel,
    activeDays = activeDaysMask.toDayOfWeekSet(),
    startTime = LocalTime.ofSecondOfDay(startTimeMinutes * 60L),
    endTime = LocalTime.ofSecondOfDay(endTimeMinutes * 60L),
    enabled = enabled,
    pausedDate = pausedDateEpochDay?.let(LocalDate::ofEpochDay),
)

fun CommuteRoutine.toEntity(): RoutineEntity = RoutineEntity(
    id = id,
    name = name,
    siteId = siteId,
    siteName = siteName,
    transportMode = transportMode.name,
    lineId = lineId,
    lineDesignation = lineDesignation,
    directionCode = directionCode,
    destinationLabel = destinationLabel,
    activeDaysMask = activeDays.toMask(),
    startTimeMinutes = startTime.toSecondOfDay() / 60,
    endTimeMinutes = endTime.toSecondOfDay() / 60,
    enabled = enabled,
    pausedDateEpochDay = pausedDate?.toEpochDay(),
)
