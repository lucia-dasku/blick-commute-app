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
data class JourneyDisruptionNoticeDto(val text: String, val effect: String, val details: String? = null)

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
    // Default covers the same stale-deployment case as `disruptions` always has -- see
    // JourneyDisruptionNotice's own doc.
    val disruptionNotices: List<JourneyDisruptionNoticeDto> = emptyList(),
)

@Serializable data class JourneysResponseDto(val fetchedAt: String, val journeys: List<JourneyPlanDto>)

/** One PRIMARY transit leg's own transport mode + line designation — a WALK leg or one with no
 * line designation carries no line-scope signal and must never be included (see
 * [se.blick.app.data.repository.JourneyRepository.getRelevantDeviationNotices]'s own filtering). */
@Serializable data class JourneyDisruptionRelevanceLegDto(val transportMode: String, val lineDesignation: String)

/** Request body for `POST /api/v1/journeys/disruptions` — see that route's own doc
 * (`backend/src/routes/journeyDisruptions.ts`). [journeyPlannerNotices] reuses the exact same
 * [JourneyDisruptionNoticeDto] shape as [JourneyPlanDto.disruptionNotices] — PRIMARY's own
 * already-fetched notices, sent back unchanged so the backend can perform the full combine/
 * dedupe/merge in one authoritative place (see
 * [se.blick.app.domain.model.ResolvedJourneyDisruption]'s own doc); [JourneyDisruptionNoticeDto.details]
 * is always null here (Journey Planner notices have none), matching what the backend already
 * expects and ignores if present. */
@Serializable
data class JourneyDisruptionRelevanceRequestDto(
    val legs: List<JourneyDisruptionRelevanceLegDto>,
    val originSiteId: Long? = null,
    val journeyPlannerNotices: List<JourneyDisruptionNoticeDto>,
)

/** One already-resolved exact-destination disruption — see
 * [se.blick.app.domain.model.ResolvedJourneyDisruption]'s own doc for the domain-layer shape this
 * maps to one-for-one, and `backend/src/domain/disruptionRelevance.ts` for how the backend
 * produces it. [relevance] is one of `"CONFIRMED"`/`"LINE_RELEVANT"`; [source] is one of
 * `"JOURNEY_PLANNER"`/`"SL_DEVIATIONS"` — both parsed defensively (see
 * [se.blick.app.data.repository.JourneyRepository]'s own mapping), never assumed exhaustive on the
 * wire. */
@Serializable
data class ResolvedJourneyDisruptionDto(
    val id: String? = null,
    val headline: String,
    val details: String? = null,
    val effect: String,
    val relevance: String,
    val source: String,
    val matchedLineDesignations: List<String> = emptyList(),
)

/** Response shape for `POST /api/v1/journeys/disruptions`. */
@Serializable data class JourneyDisruptionRelevanceResponseDto(val fetchedAt: String, val disruptions: List<ResolvedJourneyDisruptionDto>)
