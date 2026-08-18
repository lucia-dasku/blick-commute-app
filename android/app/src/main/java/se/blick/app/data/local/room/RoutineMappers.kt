package se.blick.app.data.local.room

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toExactDestinationChangesPreference
import se.blick.app.domain.model.toRoutineLabelOrNull
import se.blick.app.domain.model.toTransportMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import se.blick.app.domain.model.RoutineType

private fun Set<DayOfWeek>.toMask(): Int =
    fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

private fun Int.toDayOfWeekSet(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { day -> (this shr (day.value - 1)) and 1 == 1 }.toSet()

private fun String.toJourneyTransportModes(): Set<TransportMode> =
    split(',')
        .mapNotNull { value -> runCatching { TransportMode.valueOf(value) }.getOrNull() }
        .filter(JOURNEY_TRANSPORT_MODE_OPTIONS::contains)
        .toSet()
        .ifEmpty { DEFAULT_JOURNEY_TRANSPORT_MODES }

private fun Set<TransportMode>.toPersistedJourneyTransportModes(): String =
    JOURNEY_TRANSPORT_MODE_OPTIONS.filter(::contains).joinToString(",") { it.name }

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
    type = runCatching { RoutineType.valueOf(routineType) }.getOrDefault(RoutineType.LINE_DIRECTION),
    journeyOriginId = journeyOriginId,
    journeyOriginName = journeyOriginName,
    journeyDestinationId = journeyDestinationId,
    journeyDestinationName = journeyDestinationName,
    allowedJourneyTransportModes = allowedJourneyTransportModes.toJourneyTransportModes(),
    changesPreference = changesPreference.toExactDestinationChangesPreference(),
    label = label.toRoutineLabelOrNull(),
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
    routineType = type.name,
    journeyOriginId = journeyOriginId,
    journeyOriginName = journeyOriginName,
    journeyDestinationId = journeyDestinationId,
    journeyDestinationName = journeyDestinationName,
    allowedJourneyTransportModes = allowedJourneyTransportModes.toPersistedJourneyTransportModes(),
    changesPreference = changesPreference.name,
    label = label?.name,
)
