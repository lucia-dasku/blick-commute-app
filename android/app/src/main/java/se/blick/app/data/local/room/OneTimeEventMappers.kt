package se.blick.app.data.local.room

import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun OneTimeEventEntity.toDomain() = OneTimeEvent(
    id = id,
    label = runCatching { OneTimeEventLabel.valueOf(label) }.getOrDefault(OneTimeEventLabel.OTHER),
    name = name,
    originId = originId,
    originName = originName,
    destinationId = destinationId,
    destinationName = destinationName,
    date = LocalDate.ofEpochDay(eventDateEpochDay),
    time = LocalTime.of(eventTimeMinutes / 60, eventTimeMinutes % 60),
    timeType = runCatching { OneTimeEventTimeType.valueOf(timeType) }
        .getOrDefault(OneTimeEventTimeType.ARRIVE_BY),
    createdAt = Instant.ofEpochMilli(createdAtEpochMilli),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMilli),
)

fun OneTimeEvent.toEntity() = OneTimeEventEntity(
    id = id,
    label = label.name,
    name = name,
    originId = originId,
    originName = originName,
    destinationId = destinationId,
    destinationName = destinationName,
    eventDateEpochDay = date.toEpochDay(),
    eventTimeMinutes = time.hour * 60 + time.minute,
    timeType = timeType.name,
    createdAtEpochMilli = createdAt.toEpochMilli(),
    updatedAtEpochMilli = updatedAt.toEpochMilli(),
)
