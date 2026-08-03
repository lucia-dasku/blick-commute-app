package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LineRefDto(
    val id: Long,
    val designation: String,
    val transportMode: String,
)

@Serializable
data class StopAreaRefDto(val id: Long, val name: String, val type: String? = null)

@Serializable
data class StopPointRefDto(val id: Long, val name: String, val designation: String? = null)

@Serializable
data class JourneyDto(
    val id: Long,
    val state: String,
    val predictionState: String? = null,
)

@Serializable
data class TripDeviationDto(
    val importanceLevel: Int,
    val consequence: String,
    val message: String,
)

/**
 * Mirrors docs/api-contract.md §3 exactly, including field nullability:
 * `scheduledTime` is required, `expectedTime` is nullable (not guaranteed by the
 * upstream spec — see §1/§3), and there is deliberately no `minutesRemaining` field.
 */
@Serializable
data class DepartureDto(
    val departureId: String,
    val line: LineRefDto,
    val direction: String? = null,
    val directionCode: Int? = null,
    val destination: String? = null,
    val via: String? = null,
    val stopArea: StopAreaRefDto,
    val stopPoint: StopPointRefDto,
    val scheduledTime: String,
    val expectedTime: String? = null,
    val state: String,
    val isCancelled: Boolean,
    val journey: JourneyDto,
    val tripDeviations: List<TripDeviationDto> = emptyList(),
)

/** Shared with [DisruptionDto.affectedStopAreas] — see [se.blick.app.domain.model.SiteDeviationStopAreaRef]'s
 * own doc for why this DTO survives even though the rest of the old embedded per-departure
 * "site deviation" DTOs were removed as dead code. */
@Serializable
data class SiteDeviationStopAreaRefDto(val id: Long, val name: String, val type: String? = null)

/**
 * The backend still includes a `siteDeviations` field in this response (a documented,
 * contract-visible field — see docs/api-contract.md) that Android has never consumed; it is
 * deliberately not modeled here. `Json` is configured with `ignoreUnknownKeys = true` (see
 * `di/NetworkModule.kt`), so the extra field is silently ignored during deserialization.
 */
@Serializable
data class DeparturesResponseDto(
    val fetchedAt: String,
    val timeZone: String,
    val siteId: Long,
    val departures: List<DepartureDto>,
)
