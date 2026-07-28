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

@Serializable
data class SiteDeviationStopAreaRefDto(val id: Long, val name: String, val type: String? = null)

@Serializable
data class SiteDeviationStopPointRefDto(val id: Long, val name: String)

@Serializable
data class SiteDeviationLineRefDto(val id: Long, val designation: String, val transportMode: String? = null)

@Serializable
data class SiteDeviationDto(
    val id: Long,
    val importanceLevel: Int,
    val message: String,
    val affectedStopAreas: List<SiteDeviationStopAreaRefDto> = emptyList(),
    val affectedStopPoints: List<SiteDeviationStopPointRefDto> = emptyList(),
    val affectedLines: List<SiteDeviationLineRefDto> = emptyList(),
)

@Serializable
data class DeparturesResponseDto(
    val fetchedAt: String,
    val timeZone: String,
    val siteId: Long,
    val departures: List<DepartureDto>,
    val siteDeviations: List<SiteDeviationDto> = emptyList(),
)
