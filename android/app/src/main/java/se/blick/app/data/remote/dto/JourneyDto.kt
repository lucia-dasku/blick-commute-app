package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class JourneyLocationDto(val id: String, val name: String)
@Serializable data class JourneyLocationSearchDto(val query: String, val locations: List<JourneyLocationDto>)

@Serializable
data class JourneyLegDto(
    val transportMode: String,
    val lineDesignation: String? = null,
    val direction: String? = null,
    val originName: String,
    val destinationName: String,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val isRealtime: Boolean,
    val disruptions: List<String> = emptyList(),
)

@Serializable
data class JourneyPlanDto(
    val journeyId: String,
    val originName: String,
    val destinationName: String,
    val departureTime: String,
    val arrivalTime: String,
    val transferCount: Int,
    val firstLeg: JourneyLegDto,
    val legs: List<JourneyLegDto>,
    val disruptions: List<String> = emptyList(),
    // Default covers a response from a stale cached/proxied deployment predating this field --
    // JourneyRepository's own toJourneyRole() mapping fails closed (drops the journey) for a
    // null, unrecognized, or otherwise malformed value, rather than inventing a role for it.
    val role: String? = null,
)

@Serializable data class JourneysResponseDto(val fetchedAt: String, val journeys: List<JourneyPlanDto>)
