package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.JourneyDisruptionNoticeDto
import se.blick.app.data.remote.dto.JourneyLegDto
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toDisruptionEffect
import se.blick.app.domain.model.toJourneyRole
import se.blick.app.domain.model.toTransportMode
import java.time.Instant
import javax.inject.Inject

interface JourneyRepository {
    suspend fun searchLocations(query: String): List<JourneyLocation>
    /** [searchUntil] bounds how far forward the backend's own targeted NEXT/ALTERNATIVE
     * acquisition may search — see [se.blick.app.domain.usecase.GetRankedJourneysUseCase]'s own
     * doc. Null when the caller has no genuine routine-occurrence boundary to offer.
     * [changesPreference] — see [ExactDestinationChangesPreference]'s own doc — narrows which
     * journeys are eligible at all; defaults to [ExactDestinationChangesPreference.BOTH] (the
     * pre-existing, unfiltered behavior) so a caller predating this parameter is unaffected. */
    suspend fun getJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant? = null,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
    ): List<JourneyPlan>
}

class RemoteJourneyRepository @Inject constructor(private val apiClient: BlickApiClient) : JourneyRepository {
    override suspend fun searchLocations(query: String) =
        apiClient.searchJourneyLocations(query).locations.map { JourneyLocation(it.id, it.name) }

    override suspend fun getJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant?,
        changesPreference: ExactDestinationChangesPreference,
    ) = apiClient.getJourneys(
        originId,
        destinationId,
        JOURNEY_TRANSPORT_MODE_OPTIONS.filter(allowedTransportModes::contains).joinToString(",") { it.name },
        searchUntil?.toString(),
        changesPreference.name,
    ).journeys.mapNotNull { dto ->
            // Fail closed, never invent a role -- see toJourneyRole's own doc. A single
            // malformed entry is dropped rather than failing the whole response: the
            // remaining, validly-roled journeys are still genuinely useful to show, and
            // dropping one entry from a list never corrupts the relative order of the rest.
            val role = dto.role.toJourneyRole() ?: return@mapNotNull null
            JourneyPlan(
                dto.journeyId, dto.originName, dto.destinationName,
                Instant.parse(dto.departureTime), Instant.parse(dto.arrivalTime), dto.transferCount,
                dto.firstLeg.toDomain(), dto.legs.map(JourneyLegDto::toDomain), dto.disruptions,
                role, dto.disruptionNotices.map(JourneyDisruptionNoticeDto::toDomain),
            )
    }
}

private fun JourneyLegDto.toDomain() = JourneyLeg(
    transportMode.toTransportMode(), lineDesignation, direction, originName, destinationName,
    departureTime?.let(Instant::parse), arrivalTime?.let(Instant::parse), isRealtime, disruptions,
)

private fun JourneyDisruptionNoticeDto.toDomain() = JourneyDisruptionNotice(text, effect.toDisruptionEffect())
