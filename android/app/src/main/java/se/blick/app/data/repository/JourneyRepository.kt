package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.JourneyLegDto
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toTransportMode
import java.time.Instant
import javax.inject.Inject

interface JourneyRepository {
    suspend fun searchLocations(query: String): List<JourneyLocation>
    suspend fun getJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
    ): List<JourneyPlan>
}

class RemoteJourneyRepository @Inject constructor(private val apiClient: BlickApiClient) : JourneyRepository {
    override suspend fun searchLocations(query: String) =
        apiClient.searchJourneyLocations(query).locations.map { JourneyLocation(it.id, it.name) }

    override suspend fun getJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
    ) = apiClient.getJourneys(
        originId,
        destinationId,
        JOURNEY_TRANSPORT_MODE_OPTIONS.filter(allowedTransportModes::contains).joinToString(",") { it.name },
    ).journeys.map { dto ->
            JourneyPlan(
                dto.journeyId, dto.originName, dto.destinationName,
                Instant.parse(dto.departureTime), Instant.parse(dto.arrivalTime), dto.transferCount,
                dto.firstLeg.toDomain(), dto.legs.map(JourneyLegDto::toDomain), dto.disruptions,
            )
    }
}

private fun JourneyLegDto.toDomain() = JourneyLeg(
    transportMode.toTransportMode(), lineDesignation, direction, originName, destinationName,
    departureTime?.let(Instant::parse), arrivalTime?.let(Instant::parse), isRealtime, disruptions,
)
