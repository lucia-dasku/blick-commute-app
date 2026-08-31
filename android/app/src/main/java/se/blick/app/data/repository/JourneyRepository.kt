package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.JourneyDisruptionContextDto
import se.blick.app.data.remote.dto.JourneyDisruptionContextLegDto
import se.blick.app.data.remote.dto.JourneyDisruptionNoticeDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceLegDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceRequestDto
import se.blick.app.data.remote.dto.JourneyLegDto
import se.blick.app.data.remote.dto.JourneyContextDto
import se.blick.app.data.remote.dto.JourneySearchModeDto
import se.blick.app.data.remote.dto.JourneyPlanDto
import se.blick.app.data.remote.dto.JourneysResponseDto
import se.blick.app.data.remote.dto.ResolvedJourneyDisruptionDto
import se.blick.app.domain.model.DisruptionRelevance
import se.blick.app.domain.model.DisruptionSource
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyDisruptionContext
import se.blick.app.domain.model.JourneyDisruptionContextLeg
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.LaterJourneyOption
import se.blick.app.domain.model.LiveJourneyOptions
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.PlannedJourneyChoice
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toDisruptionEffect
import se.blick.app.domain.model.toJourneyRole
import se.blick.app.domain.model.toPlannedJourneyRole
import se.blick.app.domain.model.toTransportMode
import java.time.Instant
import java.time.ZonedDateTime
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

    /** Foreground-only additive request. Background callers keep using [getJourneys], which
     * requests and returns authoritative roles only. The default keeps existing fakes safe. */
    suspend fun getJourneyOptions(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant? = null,
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
        laterJourneyCount: Int,
    ): LiveJourneyOptions = LiveJourneyOptions(
        journeys = getJourneys(originId, destinationId, allowedTransportModes, searchUntil, changesPreference),
        laterJourneys = emptyList(),
    )

    suspend fun getPlannedJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchMode: JourneySearchMode,
        requestedDateTime: ZonedDateTime,
    ): PlannedJourneyResult = throw UnsupportedOperationException("Planned journey search is not implemented")

    /** Resolves the current PRIMARY exact-destination journey's own live disruption relevance —
     * see `backend/src/routes/journeyDisruptions.ts`'s own doc. The backend is the single
     * authoritative source: it combines [journeyPlannerNotices] (PRIMARY's own already-fetched
     * `disruptionNotices`, sent back unchanged — see
     * [se.blick.app.domain.usecase.primaryDisruptionNotices]) with structurally-matched SL
     * Deviations from its own shared cached snapshot, deduplicates, and resolves each to
     * [DisruptionRelevance.CONFIRMED] or [DisruptionRelevance.LINE_RELEVANT] — this app performs
     * no relevance inference of its own, only rendering.
     *
     * A WALK leg or a leg with no [JourneyLeg.lineDesignation] in [legs] carries no line-scope
     * signal and is never sent (see [RemoteJourneyRepository]'s own filtering). When there is
     * nothing at all to resolve (no eligible leg AND no Journey Planner notice), this returns an
     * empty list without making a network call. [originSiteId] is the routine's own
     * SL-Transport-namespace origin site id (see [se.blick.app.domain.model.CommuteRoutine.siteId]),
     * or null when unavailable.
     *
     * [disruptionContext] is PRIMARY's own [JourneyPlan.disruptionContext], sent back completely
     * unchanged — this app never reads an individual field out of it (see that type's own "dumb
     * pass-through" doc). Trailing and defaulted to null, like every other addition to this
     * interface, so no existing positional override of this method anywhere in this codebase can
     * have a later positional argument silently rebind to a new parameter instead. [departureTime]/
     * [arrivalTime] are PRIMARY's own [JourneyPlan.departureTime]/[JourneyPlan.arrivalTime],
     * enabling the backend's own temporal-relevance check — omitted entirely (both default to
     * null) simply skips that check, exactly as it was always skipped before this feature existed.
     *
     * [journeyOriginId]/[journeyDestinationId] are the routine's own
     * [se.blick.app.domain.model.CommuteRoutine.journeyOriginId]/`.journeyDestinationId` — the
     * exact same ids already sent to [getJourneys]' own `originId`/`destinationId`, resent
     * unchanged so the backend can resolve its own "requested normal corridor" segment-parsing
     * evidence (see [JourneyDisruptionRelevanceRequestDto]'s own doc). This app never computes or
     * interprets anything from them. Trailing and defaulted to null for the same reason as every
     * other parameter above. */
    suspend fun getRelevantDeviationNotices(
        legs: List<JourneyLeg>,
        originSiteId: Long?,
        journeyPlannerNotices: List<JourneyDisruptionNotice>,
        disruptionContext: JourneyDisruptionContext? = null,
        departureTime: Instant? = null,
        arrivalTime: Instant? = null,
        journeyOriginId: String? = null,
        journeyDestinationId: String? = null,
    ): List<ResolvedJourneyDisruption> = emptyList()
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
    ): List<JourneyPlan> {
        val response = apiClient.getJourneys(
            originId,
            destinationId,
            JOURNEY_TRANSPORT_MODE_OPTIONS.filter(allowedTransportModes::contains).joinToString(",") { it.name },
            searchUntil?.toString(),
            changesPreference.name,
        )
        if (response.journeyContext != JourneyContextDto.LIVE || response.searchMode != JourneySearchModeDto.NOW) {
            throw UnexpectedJourneyContextException(
                "Expected LIVE/NOW but received ${response.journeyContext}/${response.searchMode}",
            )
        }
        return response.toDomainJourneys()
    }

    override suspend fun getJourneyOptions(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchUntil: Instant?,
        changesPreference: ExactDestinationChangesPreference,
        laterJourneyCount: Int,
    ): LiveJourneyOptions {
        val response = apiClient.getJourneys(
            originId = originId,
            destinationId = destinationId,
            transportModes = JOURNEY_TRANSPORT_MODE_OPTIONS
                .filter(allowedTransportModes::contains)
                .joinToString(",") { it.name },
            searchUntil = searchUntil?.toString(),
            changesPreference = changesPreference.name,
            laterJourneyCount = laterJourneyCount,
        )
        if (response.journeyContext != JourneyContextDto.LIVE || response.searchMode != JourneySearchModeDto.NOW) {
            throw UnexpectedJourneyContextException(
                "Expected LIVE/NOW but received ${response.journeyContext}/${response.searchMode}",
            )
        }
        val authoritative = response.toDomainJourneys()
        val authoritativeIds = authoritative.mapTo(mutableSetOf(), JourneyPlan::journeyId)
        val seenSupplemental = mutableSetOf<String>()
        val supplemental = response.laterJourneys.mapNotNull { dto ->
            if (dto.journeyId in authoritativeIds || !seenSupplemental.add(dto.journeyId)) return@mapNotNull null
            // JourneyPlan currently carries a live role for authoritative consumers. The
            // placeholder below is never observed: LaterJourneyOption is the sole authority,
            // and supplemental values never enter notifications/widgets/disruption ownership.
            LaterJourneyOption(dto.toDomain(JourneyRole.PRIMARY))
        }
        return LiveJourneyOptions(authoritative, supplemental)
    }

    override suspend fun getPlannedJourneys(
        originId: String,
        destinationId: String,
        allowedTransportModes: Set<TransportMode>,
        searchMode: JourneySearchMode,
        requestedDateTime: ZonedDateTime,
    ): PlannedJourneyResult {
        require(searchMode != JourneySearchMode.NOW) { "A planned journey requires LEAVE_AT or ARRIVE_BY" }
        val requested = requestedDateTime.toOffsetDateTime()
        val response = apiClient.getPlannedJourneys(
            originId = originId,
            destinationId = destinationId,
            transportModes = JOURNEY_TRANSPORT_MODE_OPTIONS
                .filter(allowedTransportModes::contains)
                .joinToString(",") { it.name },
            searchMode = JourneySearchModeDto.valueOf(searchMode.name),
            requestedDateTime = requested.toString(),
        )
        val responseRequestedInstant = response.requestedDateTime?.let(Instant::parse)
        if (
            response.journeyContext != JourneyContextDto.PLANNED ||
            response.searchMode.name != searchMode.name ||
            responseRequestedInstant != requested.toInstant()
        ) {
            throw UnexpectedJourneyContextException(
                "Expected PLANNED/$searchMode for ${requested.toInstant()} but received " +
                    "${response.journeyContext}/${response.searchMode}/${response.requestedDateTime}",
            )
        }
        return PlannedJourneyResult(
            fetchedAt = Instant.parse(response.fetchedAt),
            searchMode = searchMode,
            requestedDateTime = responseRequestedInstant,
            choices = response.toDomainPlannedChoices(),
        )
    }

    private fun JourneysResponseDto.toDomainJourneys() = journeys.mapNotNull { dto ->
            // Fail closed, never invent a role -- see toJourneyRole's own doc. A single
            // malformed entry is dropped rather than failing the whole response: the
            // remaining, validly-roled journeys are still genuinely useful to show, and
            // dropping one entry from a list never corrupts the relative order of the rest.
            val role = dto.role.toJourneyRole() ?: return@mapNotNull null
            dto.toDomain(role)
        }

    /** Planned roles are parsed independently from live roles. The wrapper is authoritative
     * for Event UI; the contained [JourneyPlan] is reused solely for its route/timeline data. */
    private fun JourneysResponseDto.toDomainPlannedChoices() = journeys.mapNotNull { dto ->
        val role = dto.role.toPlannedJourneyRole() ?: return@mapNotNull null
        PlannedJourneyChoice(role = role, journey = dto.toDomain(JourneyRole.PRIMARY))
    }

    private fun JourneyPlanDto.toDomain(role: JourneyRole) = JourneyPlan(
        journeyId, originName, destinationName,
        Instant.parse(departureTime), Instant.parse(arrivalTime), transferCount,
        firstLeg.toDomain(), legs.map(JourneyLegDto::toDomain), disruptions,
        role, disruptionNotices.map(JourneyDisruptionNoticeDto::toDomain),
        disruptionContext?.toDomain(),
    )

    override suspend fun getRelevantDeviationNotices(
        legs: List<JourneyLeg>,
        originSiteId: Long?,
        journeyPlannerNotices: List<JourneyDisruptionNotice>,
        disruptionContext: JourneyDisruptionContext?,
        departureTime: Instant?,
        arrivalTime: Instant?,
        journeyOriginId: String?,
        journeyDestinationId: String?,
    ): List<ResolvedJourneyDisruption> {
        // A WALK leg's own transportMode is already TransportMode.UNKNOWN by the time it reaches
        // this domain model (see String.toTransportMode()'s own doc -- Android's TransportMode
        // enum has no WALK case at all, since walking legs carry no SL line/mode to filter
        // disruptions by), so excluding UNKNOWN here also excludes every WALK leg for free.
        val encodedLegs = legs
            .filter { it.transportMode != TransportMode.UNKNOWN && it.lineDesignation != null }
            .distinctBy { it.transportMode to it.lineDesignation }
            .map { JourneyDisruptionRelevanceLegDto(it.transportMode.name, it.lineDesignation!!) }
        if (encodedLegs.isEmpty() && journeyPlannerNotices.isEmpty()) return emptyList()
        val request = JourneyDisruptionRelevanceRequestDto(
            legs = encodedLegs,
            originSiteId = originSiteId,
            journeyPlannerNotices = journeyPlannerNotices.map { JourneyDisruptionNoticeDto(it.text, it.effect.name) },
            disruptionContext = disruptionContext?.toDto(),
            departureTime = departureTime?.toString(),
            arrivalTime = arrivalTime?.toString(),
            journeyOriginId = journeyOriginId,
            journeyDestinationId = journeyDestinationId,
        )
        return apiClient.getJourneyDisruptionRelevance(request).disruptions.mapNotNull(ResolvedJourneyDisruptionDto::toDomain)
    }
}

class UnexpectedJourneyContextException(message: String) : IllegalStateException(message)

private fun JourneyLegDto.toDomain() = JourneyLeg(
    transportMode.toTransportMode(), lineDesignation, direction, originName, destinationName,
    departureTime?.let(Instant::parse), arrivalTime?.let(Instant::parse), isRealtime, disruptions,
)

private fun JourneyDisruptionNoticeDto.toDomain() = JourneyDisruptionNotice(text, effect.toDisruptionEffect(), details)

private fun JourneyDisruptionContextDto.toDomain() =
    JourneyDisruptionContext(version, journeyStart, journeyEnd, legs.map(JourneyDisruptionContextLegDto::toDomain))

private fun JourneyDisruptionContextLegDto.toDomain() = JourneyDisruptionContextLeg(
    transportMode, lineDesignation, boardingPatternPointGid, alightingPatternPointGid, stopPatternPointGids, stopSequenceComplete,
)

private fun JourneyDisruptionContext.toDto() =
    JourneyDisruptionContextDto(version, journeyStart, journeyEnd, legs.map(JourneyDisruptionContextLeg::toDto))

private fun JourneyDisruptionContextLeg.toDto() = JourneyDisruptionContextLegDto(
    transportMode, lineDesignation, boardingPatternPointGid, alightingPatternPointGid, stopPatternPointGids, stopSequenceComplete,
)

/** Fail closed, never invent a relevance/source — a single malformed entry is dropped rather
 * than failing the whole response, matching [RemoteJourneyRepository.getJourneys]'s own
 * `toJourneyRole()` convention for exactly the same reason: the remaining, validly-shaped
 * disruptions are still genuinely useful to show. */
private fun ResolvedJourneyDisruptionDto.toDomain(): ResolvedJourneyDisruption? {
    val relevance = runCatching { DisruptionRelevance.valueOf(relevance) }.getOrNull() ?: return null
    val source = runCatching { DisruptionSource.valueOf(source) }.getOrNull() ?: return null
    return ResolvedJourneyDisruption(id, headline, details, effect.toDisruptionEffect(), relevance, source, matchedLineDesignations)
}
