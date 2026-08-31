package se.blick.app.domain.model

import java.time.Instant

enum class JourneySearchMode { NOW, LEAVE_AT, ARRIVE_BY }

data class PlannedJourneyResult(
    val fetchedAt: Instant,
    val searchMode: JourneySearchMode,
    val requestedDateTime: Instant,
    val choices: List<PlannedJourneyChoice>,
)

/** A one-time Event's planned chooser role. These roles are intentionally separate from
 * [JourneyRole]: EARLIER/LATER are chronological neighbors around the backend-selected
 * [RECOMMENDED] journey and may use unrelated route families. */
enum class PlannedJourneyRole { EARLIER, RECOMMENDED, LATER }

data class PlannedJourneyChoice(
    val role: PlannedJourneyRole,
    val journey: JourneyPlan,
)

/** A role-free foreground option returned separately from the backend-authoritative live list.
 * The wrapper is the authority that this is supplemental; the contained JourneyPlan is reused
 * only for itinerary/timeline data and must never enter notification, widget or disruption
 * selection. */
data class LaterJourneyOption(val journey: JourneyPlan)

data class LiveJourneyOptions(
    val journeys: List<JourneyPlan>,
    val laterJourneys: List<LaterJourneyOption>,
)

/** Fail-closed counterpart to [toJourneyRole] for the planned Event contract. */
fun String?.toPlannedJourneyRole(): PlannedJourneyRole? =
    this?.let { runCatching { PlannedJourneyRole.valueOf(it) }.getOrNull() }

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

/**
 * A journey's semantic role within its routine's exact-destination result, assigned by the
 * backend (see `backend/src/routes/journeys.ts`'s own doc) since only it has the full candidate
 * set -- including targeted follow-up SL searches this app never sees -- needed to compare
 * journeys structurally (same route family: same transit legs, same transport mode and stops per
 * leg, regardless of line designation -- see `backend/src/domain/routePattern.ts`) and by Pareto
 * dominance (see `backend/src/domain/dominance.ts`). [PRIMARY] is the current regular route
 * family's own departure to catch right now; [NEXT] is the next departure in that SAME route
 * family if you miss it; [ALTERNATIVE] is a genuinely useful journey from a DIFFERENT route
 * family that departs after [PRIMARY], before [NEXT], and arrives before [NEXT] does -- never a
 * fixed minute-based gap or arrival-advantage threshold. Consumers must render off this field,
 * never off a journey's position within [se.blick.app.domain.usecase.GetRankedJourneysUseCase]'s
 * result list.
 */
enum class JourneyRole { PRIMARY, NEXT, ALTERNATIVE }

/** Defensive, FAIL-CLOSED parse of the backend's own role string. Backend roles are
 * authoritative (see this enum's own doc) — an invalid, unrecognized, or entirely absent
 * value must never silently become [JourneyRole.PRIMARY] (or any other specific role):
 * doing so would let a malformed response — a backend rollout/rollback mismatch, a stale
 * cached response predating this field, or a genuine bug — render as though it were a
 * trustworthy PRIMARY departure. Returns `null` for anything that isn't exactly one of
 * [JourneyRole.entries]'s own names; a caller must treat `null` as "this journey's role
 * could not be established" and act accordingly — see
 * [se.blick.app.data.repository.RemoteJourneyRepository], which drops the whole journey
 * from its mapped result rather than ever inventing a role for it. */
fun String?.toJourneyRole(): JourneyRole? =
    this?.let { runCatching { JourneyRole.valueOf(it) }.getOrNull() }

/**
 * A disruption notice for the current PRIMARY journey, classified into the same nine
 * passenger-facing [DisruptionEffect]s `/api/v1/disruptions` already uses (see
 * `backend/src/normalize/classifyDisruptionEffect.ts`) — never a second, independent
 * classification. [text] is SL's own unmodified notice text (never translated or reinterpreted).
 *
 * This shape is only ever populated from Journey Planner's own `infos`
 * (`JourneyPlan.disruptionNotices`, unchanged) — the backend already deduplicates identical text
 * repeated across legs before this ever reaches Android (see
 * `backend/src/normalize/normalizeJourney.ts`'s own doc). [details] is always `null` here —
 * Journey Planner notices have no separate longer body the way an SL Deviations message does. Sent
 * to `POST /api/v1/journeys/disruptions` as-is (see
 * [se.blick.app.domain.usecase.primaryDisruptionNotices]'s own doc); the backend's own
 * `ResolvedJourneyDisruption` resolver is what combines it with matched SL Deviations — Android
 * itself never performs that combination (see `backend/src/domain/disruptionRelevance.ts`'s own
 * doc for the full matching rules).
 */
data class JourneyDisruptionNotice(val text: String, val effect: DisruptionEffect, val details: String? = null)

/** One PRIMARY transit leg's own structural boarding/alighting/travelled-stop identity — see
 * `backend/src/models/journeyDisruptionContext.ts`'s own doc for the full field contract. Every
 * field here is opaque to Android: nothing in this app ever branches on
 * [boardingPatternPointGid]/[alightingPatternPointGid]/[stopPatternPointGids]/[stopSequenceComplete]
 * — they exist purely to be carried, unread, from [JourneyPlan.disruptionContext] back to
 * `POST /api/v1/journeys/disruptions` (see [JourneyDisruptionContext]'s own doc). */
data class JourneyDisruptionContextLeg(
    val transportMode: String,
    val lineDesignation: String?,
    val boardingPatternPointGid: String?,
    val alightingPatternPointGid: String?,
    val stopPatternPointGids: List<String>,
    val stopSequenceComplete: Boolean,
)

/**
 * Additive structural metadata attached to a [JourneyPlan] alongside [JourneyPlan.disruptions]/
 * [JourneyPlan.disruptionNotices] — backend-produced, backend-consumed. Android's own single
 * responsibility for this type is to retain it unchanged with whichever [JourneyPlan] currently
 * holds [JourneyRole.PRIMARY] and send it back verbatim as part of the request to
 * `POST /api/v1/journeys/disruptions` (see
 * [se.blick.app.data.repository.JourneyRepository.getRelevantDeviationNotices]); the backend's own
 * `domain/journeyDisruptionScope.ts` and `services/stopPointDirectory.ts` are what actually resolve
 * it into ACCESS_POINTS/TRAVELLED_PATH stop scopes — this app never performs that resolution, or
 * any other interpretation of [legs], itself. `null` on a [JourneyPlan] from a stale cached/proxied
 * deployment predating this field, or a journey the backend genuinely could not build one for —
 * [se.blick.app.data.repository.JourneyRepository] simply omits it from the outgoing request in
 * that case, which the backend already treats as "fall back to the pre-existing
 * `legs`/`originSiteId`-only PARTIAL resolution".
 */
data class JourneyDisruptionContext(
    val version: Int,
    val journeyStart: String,
    val journeyEnd: String,
    val legs: List<JourneyDisruptionContextLeg>,
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
    val role: JourneyRole = JourneyRole.PRIMARY,
    /** Additive alongside [disruptions] (the existing raw text, unchanged) — see
     * [JourneyDisruptionNotice]'s own doc. Defaults to empty so every existing positional/named
     * test construction across this codebase keeps compiling unchanged. */
    val disruptionNotices: List<JourneyDisruptionNotice> = emptyList(),
    /** Additive — see [JourneyDisruptionContext]'s own doc. Defaults to null so every existing
     * positional/named test construction across this codebase keeps compiling unchanged. */
    val disruptionContext: JourneyDisruptionContext? = null,
)
