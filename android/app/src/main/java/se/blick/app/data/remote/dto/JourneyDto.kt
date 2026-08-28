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

/** One PRIMARY transit leg's own structural boarding/alighting/travelled-stop identity — see
 * `backend/src/models/journeyDisruptionContext.ts`'s own doc for the full field contract. Android
 * never reads an individual field of this type for any relevance decision of its own: it is
 * retained with [JourneyPlanDto] unchanged and sent back verbatim as part of
 * [JourneyDisruptionRelevanceRequestDto] — see [se.blick.app.data.repository.JourneyRepository]'s
 * own "dumb pass-through" doc. */
@Serializable
data class JourneyDisruptionContextLegDto(
    val transportMode: String,
    val lineDesignation: String? = null,
    val boardingPatternPointGid: String? = null,
    val alightingPatternPointGid: String? = null,
    val stopPatternPointGids: List<String> = emptyList(),
    val stopSequenceComplete: Boolean = false,
)

/** Additive structural metadata alongside [JourneyPlanDto.disruptions]/[JourneyPlanDto.disruptionNotices]
 * — see [JourneyDisruptionContextLegDto]'s own doc. Absent on a response from a stale cached/
 * proxied deployment predating this field, exactly like [JourneyPlanDto.role]/[JourneyPlanDto.disruptionNotices]
 * already are — never invented when missing. */
@Serializable
data class JourneyDisruptionContextDto(
    val version: Int,
    val journeyStart: String,
    val journeyEnd: String,
    val legs: List<JourneyDisruptionContextLegDto>,
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
    // Default covers the same stale-deployment case as `disruptions` always has -- see
    // JourneyDisruptionNotice's own doc.
    val disruptionNotices: List<JourneyDisruptionNoticeDto> = emptyList(),
    // Additive -- see JourneyDisruptionContextDto's own doc. Null both for a stale-deployment
    // response and for a journey this backend genuinely could not build one for.
    val disruptionContext: JourneyDisruptionContextDto? = null,
)

@Serializable enum class JourneyContextDto { LIVE, PLANNED }

@Serializable enum class JourneySearchModeDto { NOW, LEAVE_AT, ARRIVE_BY }

@Serializable
data class JourneysResponseDto(
    val fetchedAt: String,
    val journeys: List<JourneyPlanDto>,
    val journeyContext: JourneyContextDto = JourneyContextDto.LIVE,
    val searchMode: JourneySearchModeDto = JourneySearchModeDto.NOW,
    val requestedDateTime: String? = null,
)

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
 * expects and ignores if present.
 *
 * [disruptionContext] is PRIMARY's own [JourneyPlanDto.disruptionContext], sent back completely
 * unchanged (never a field read out of it and re-sent individually) — absent for a journey this
 * backend never attached one to, in which case the backend falls back to the pre-existing
 * `legs`/`originSiteId`-only PARTIAL resolution. [departureTime]/[arrivalTime] are PRIMARY's own
 * [JourneyPlanDto.departureTime]/[JourneyPlanDto.arrivalTime] — enabling the backend's temporal-
 * relevance check; omitted entirely simply skips that check, exactly as it was always skipped
 * before this feature existed.
 *
 * [journeyOriginId]/[journeyDestinationId] are the routine's own
 * [se.blick.app.domain.model.CommuteRoutine.journeyOriginId]/`.journeyDestinationId` — the exact
 * same opaque Journey-Planner location ids already sent as `originId`/`destinationId` to
 * `GET /api/v1/journeys` when this journey was fetched, resent here completely unchanged. This
 * app never computes or interprets anything from them — the backend alone resolves them (see
 * `backend/src/services/journeyEndpointSiteResolver.ts`) to power the segment-parsing relevance
 * enhancement's own "requested normal corridor" evidence, which is what lets a disruption stay
 * correctly confirmed even after Journey Planner has already rerouted PRIMARY around the exact
 * closed segment. Absent for a fixed-site routine (no journey origin/destination at all) or an
 * older/unrelated call site — the backend already treats that exactly like an unresolvable
 * identity bridge, never a 400. */
@Serializable
data class JourneyDisruptionRelevanceRequestDto(
    val legs: List<JourneyDisruptionRelevanceLegDto>,
    val originSiteId: Long? = null,
    val journeyPlannerNotices: List<JourneyDisruptionNoticeDto>,
    val disruptionContext: JourneyDisruptionContextDto? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val journeyOriginId: String? = null,
    val journeyDestinationId: String? = null,
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
