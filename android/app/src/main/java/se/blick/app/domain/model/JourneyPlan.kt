package se.blick.app.domain.model

import java.time.Instant

data class JourneyLocation(val id: String, val name: String)

data class JourneyLeg(
    val transportMode: TransportMode,
    val lineDesignation: String?,
    val direction: String?,
    val originName: String,
    val destinationName: String,
    val departureTime: Instant?,
    val arrivalTime: Instant?,
    val isRealtime: Boolean,
    val disruptions: List<String>,
)

data class JourneyPlan(
    val journeyId: String,
    val originName: String,
    val destinationName: String,
    val departureTime: Instant,
    val arrivalTime: Instant,
    val transferCount: Int,
    val firstLeg: JourneyLeg,
    val legs: List<JourneyLeg>,
    val disruptions: List<String>,
)
