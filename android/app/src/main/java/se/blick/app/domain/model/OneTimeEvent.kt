package se.blick.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class OneTimeEventLabel { TRAVEL, EVENT, APPOINTMENT, OTHER }

enum class OneTimeEventTimeType { ARRIVE_BY, LEAVE_AT }

val STOCKHOLM_ZONE: ZoneId = ZoneId.of("Europe/Stockholm")

/** A persisted one-off transport intention. Journey results are deliberately not stored here. */
data class OneTimeEvent(
    val id: String = UUID.randomUUID().toString(),
    val label: OneTimeEventLabel,
    val name: String,
    val originId: String,
    val originName: String,
    val destinationId: String,
    val destinationName: String,
    val date: LocalDate,
    val time: LocalTime,
    val timeType: OneTimeEventTimeType = OneTimeEventTimeType.ARRIVE_BY,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    fun targetInstant(): Instant = date.atTime(time).atZone(STOCKHOLM_ZONE).toInstant()
}

fun OneTimeEvent.isUpcoming(now: Instant): Boolean = targetInstant() >= now

fun List<OneTimeEvent>.upcomingAt(now: Instant): List<OneTimeEvent> =
    filter { it.isUpcoming(now) }.sortedBy { it.targetInstant() }

enum class OneTimeEventValidationResult { VALID, REQUIRED, PAST, SAME_LOCATION }

fun validateOneTimeEvent(
    origin: JourneyLocation?,
    destination: JourneyLocation?,
    date: LocalDate,
    time: LocalTime,
    now: Instant,
): OneTimeEventValidationResult {
    if (origin == null || destination == null) return OneTimeEventValidationResult.REQUIRED
    if (origin.id == destination.id) return OneTimeEventValidationResult.SAME_LOCATION
    if (date.atTime(time).atZone(STOCKHOLM_ZONE).toInstant() <= now) return OneTimeEventValidationResult.PAST
    return OneTimeEventValidationResult.VALID
}
