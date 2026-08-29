package se.blick.app.domain.model

import java.time.Instant

data class DisruptionMessage(
    val header: String,
    val details: String,
    val scopeAlias: String?,
    val webLink: String?,
    val language: String,
)

data class DisruptionPriority(val importance: Int, val influence: Int, val urgency: Int)

data class DisruptionAffectedLine(
    val id: Long,
    val designation: String,
    val transportMode: TransportMode,
    val name: String?,
)

data class Disruption(
    val disruptionId: String,
    val version: Int,
    val createdAt: Instant,
    val modifiedAt: Instant?,
    val validFrom: Instant?,
    val validUntil: Instant?,
    val priority: DisruptionPriority,
    val message: DisruptionMessage,
    val affectedStopAreas: List<SiteDeviationStopAreaRef>,
    val affectedLines: List<DisruptionAffectedLine>,
    val affectedModes: List<TransportMode>,
    /** Trailing, defaulted (not inserted earlier in the constructor) so existing positional/named
     * call sites across the test suite keep compiling unchanged — same convention as
     * [se.blick.app.notification.RoutineNotificationModel]'s own trailing disruption fields. See
     * [DisruptionEffect]'s own doc for why [DisruptionEffect.DISRUPTION] is always a safe default. */
    val effect: DisruptionEffect = DisruptionEffect.DISRUPTION,
)

/** One backend disruption response kept as a unit so the source snapshot timestamp cannot be
 * lost or incorrectly replaced with the Android response-arrival time. [sourceFetchedAt] is null
 * only when the backend's timestamp was malformed; callers may still use [disruptions] as a
 * conservative fallback, but must not treat their source data as fresh. */
data class DisruptionsSnapshot(
    val disruptions: List<Disruption>,
    val sourceFetchedAt: Instant?,
)

/**
 * The small, shared shape [se.blick.app.notification.RoutineNotificationMapper],
 * [se.blick.app.widget.RoutineWidgetMapper], and Routine Details' own disruption cards actually
 * consume — deliberately NOT a full [Disruption]: an exact-destination journey's own
 * [ResolvedJourneyDisruption] has no disruption version, SL Deviations priority, affected
 * stop areas, or validity range, and constructing a fake [Disruption] with invented values for
 * those just to satisfy a mapper that only ever reads `message.header`/`message.details`/`effect`
 * would be worse than a small, purpose-built type both sources can produce honestly.
 *
 * [Disruption]'s own `message.header`/`message.details`/`effect` map onto [headline]/[details]/
 * [effect] one-for-one for the `LINE_DIRECTION` path (see [toPresentation]), which is always
 * CONFIRMED-equivalent by construction (a siteId-scoped `/api/v1/disruptions` query is already
 * proven relevant — there is no `LINE_RELEVANT` concept on that path at all, hence
 * [uncertainLineDesignations] always empty there). A [ResolvedJourneyDisruption]'s own
 * `headline`/`details`/`effect` map onto these same fields one-for-one for the
 * `EXACT_DESTINATION` path (see [toPresentation]) REGARDLESS of its own `relevance` — Routine
 * Details always shows the real SL text for both `CONFIRMED` and `LINE_RELEVANT` (see that
 * screen's own doc) — but a `LINE_RELEVANT` result ALSO populates [uncertainLineDesignations],
 * which the notification/widget's own COMPACT presentation must check first (see that field's own
 * doc) rather than presenting [headline]/[effect] as proven for this journey's exact segment.
 */
data class DisruptionPresentation(
    val headline: String,
    val details: String?,
    val effect: DisruptionEffect,
    /** Non-empty ONLY when this presentation's own source disruption resolved to
     * [DisruptionRelevance.LINE_RELEVANT] (see [ResolvedJourneyDisruption.relevance]'s own doc) —
     * the distinct PRIMARY line designation(s) SL's structured scope matched, without structured
     * proof the affected segment intersects this exact journey. [headline]/[details]/[effect]
     * still carry the disruption's own real SL text/classification (read as-is by Routine
     * Details — see that screen's own doc), but the notification's
     * ([se.blick.app.notification.RoutineNotificationBuilder]) and widget's
     * ([se.blick.app.widget.BlickRoutineWidget]) own COMPACT presentation must use THIS field
     * instead of [headline]/[effect] whenever it is non-empty: a single entry becomes a
     * localized "Line 11 disruption"-style label; more than one becomes a generic localized
     * fallback (never a concatenated list) — never [headline]'s own specific SL text, and never
     * [effect]'s own specific classified label, both of which would overclaim proof this
     * exact journey segment is affected. Always empty for `LINE_DIRECTION` (see this type's own
     * doc) and for a `CONFIRMED` exact-destination disruption, both of which show
     * [headline]/[effect] directly in every surface, exactly as before this field existed. */
    val uncertainLineDesignations: List<String> = emptyList(),
)

/** Adapts a real SL Deviations [Disruption] (the `LINE_DIRECTION` path) to the shared
 * [DisruptionPresentation] shape every disruption consumer now reads from — see that type's own
 * doc. Always [DisruptionPresentation.uncertainLineDesignations] empty: a `LINE_DIRECTION`
 * routine's own siteId-scoped `/api/v1/disruptions` query is already structurally proven relevant
 * by construction, so there is no `LINE_RELEVANT`-equivalent uncertainty on this path at all. */
fun Disruption.toPresentation(): DisruptionPresentation =
    DisruptionPresentation(headline = message.header, details = message.details, effect = effect)

/**
 * How confidently a [ResolvedJourneyDisruption] is known to affect the current exact-destination
 * journey — the backend's own authoritative resolution (see
 * `backend/src/domain/disruptionRelevance.ts`'s own doc for the full matching rules and the
 * identifier-namespace evidence they are built on). Android performs no relevance inference of
 * its own: it renders exactly what the backend resolved.
 *
 * - [CONFIRMED]: structured evidence proves this disruption affects the journey — either a
 *   Journey Planner notice was attached directly to PRIMARY (the strongest possible evidence), or
 *   an SL Deviation's line/mode scope AND a verified stop-area intersection both hold. The real
 *   classified [ResolvedJourneyDisruption.effect] may be presented as definitely true.
 * - [LINE_RELEVANT]: an SL Deviation's line/mode scope matches a PRIMARY leg, but the backend
 *   could not prove the affected segment/stop intersects this exact journey. The real classified
 *   effect must NOT be presented as proven for this journey's own segment — see
 *   [DisruptionPresentation.uncertainLineDesignations]'s own doc for the conservative
 *   presentation this requires instead.
 */
enum class DisruptionRelevance { CONFIRMED, LINE_RELEVANT }

/** Which of the two backend sources produced a [ResolvedJourneyDisruption] — see that type's own
 * doc. Currently unused by any Android rendering decision (both sources render identically once
 * resolved), kept only because the backend already exposes it and discarding it on the wire would
 * lose real provenance information for free — a future feature (e.g. a debug/diagnostic view)
 * could use it without any backend change. */
enum class DisruptionSource { JOURNEY_PLANNER, SL_DEVIATIONS }

/**
 * One already-resolved exact-destination disruption — the ONLY shape Android receives from
 * `POST /api/v1/journeys/disruptions` (see [se.blick.app.data.repository.JourneyRepository]'s own
 * `getRelevantDeviationNotices` doc). Deliberately NOT an overload of [JourneyDisruptionNotice]:
 * that type remains exactly what it always was — a Journey Planner `infos` notice, `{text,
 * effect}`, sent AS INPUT to the resolver (see
 * [se.blick.app.domain.usecase.primaryDisruptionNotices]) — while this type is the resolver's own
 * OUTPUT, carrying strictly more semantic information ([relevance], [source],
 * [matchedLineDesignations]) that [JourneyDisruptionNotice] was never meant to hold.
 */
data class ResolvedJourneyDisruption(
    /** The SL Deviations `disruptionId`, when [source] is [DisruptionSource.SL_DEVIATIONS].
     * Always null for a [DisruptionSource.JOURNEY_PLANNER]-sourced entry, which carries no stable
     * upstream id. */
    val id: String?,
    /** SL's own unmodified text — never translated, summarized, or replaced by the classification
     * label. Always the disruption's real header, REGARDLESS of [relevance] — see
     * [DisruptionPresentation.uncertainLineDesignations]'s own doc for which surfaces may still
     * show this directly when [relevance] is [DisruptionRelevance.LINE_RELEVANT]. */
    val headline: String,
    /** An SL Deviation's own longer body text. Always null for a
     * [DisruptionSource.JOURNEY_PLANNER]-sourced entry. */
    val details: String?,
    val effect: DisruptionEffect,
    val relevance: DisruptionRelevance,
    val source: DisruptionSource,
    /** The distinct PRIMARY leg line designation(s) whose line/mode matched — always empty for a
     * [DisruptionSource.JOURNEY_PLANNER]-sourced entry (never matched by line at all; already
     * journey-scoped by Journey Planner itself). Populated for an [DisruptionSource.SL_DEVIATIONS]
     * entry regardless of [relevance]. */
    val matchedLineDesignations: List<String>,
)

/** Adapts one resolved disruption to the shared [DisruptionPresentation] shape — see that type's
 * own doc for exactly which fields carry the real SL text/effect versus the conservative
 * line-only warning. */
fun ResolvedJourneyDisruption.toPresentation(): DisruptionPresentation = DisruptionPresentation(
    headline = headline,
    details = details,
    effect = effect,
    uncertainLineDesignations = if (relevance == DisruptionRelevance.LINE_RELEVANT) matchedLineDesignations else emptyList(),
)

/**
 * Higher [DisruptionPriority.importance] first, then [DisruptionPriority.influence], then
 * [DisruptionPriority.urgency] — upstream (SL) documents these three fields only as sort
 * hints, with no defined combined ordering or stated direction (see
 * docs/api-contract.md §3.3), so "higher number = more important" is this app's own
 * assumption, applied consistently everywhere a single ranking is needed (the Routine
 * Details disruptions section, and picking the one disruption shown in the notification).
 * The checked-in SL sample has only two entries and puts the lower numeric priority first, so
 * it is not evidence for either direction; backend classification diagnostics include all three
 * raw priority fields to support a later evidence-based decision without changing behavior now.
 */
private val disruptionPriorityComparator: Comparator<Disruption> =
    compareByDescending<Disruption> { it.priority.importance }
        .thenByDescending { it.priority.influence }
        .thenByDescending { it.priority.urgency }

/**
 * The disruptions actually worth showing right now, as of [now]: expired ones dropped
 * (`validUntil` in the past — a defensive re-check on top of the backend's own `future=false`
 * filtering, since a value served from [se.blick.app.data.remote.cache.DisruptionCache] can be
 * up to a minute old by the time this runs), duplicate [disruptionId]s collapsed to their
 * highest [Disruption.version] (upstream has been observed to repeat the same case under
 * multiple scope entries), ordered by [disruptionPriorityComparator] — the single ordering both
 * the Routine Details section and the notification's "highest-priority disruption" pick rely
 * on — and finally collapsed once more by identical message content: SL Deviations can publish
 * the very same rider-facing text (header + details) as separate deviation cases scoped to
 * different, overlapping stop-area/line combinations, which the [disruptionId]-based step above
 * does not catch since each case has its own id. Applied AFTER the priority sort, so of two
 * entries sharing the same text, the higher-priority one is always the one kept.
 */
fun List<Disruption>.relevantDisruptions(now: Instant): List<Disruption> =
    asSequence()
        .filter { it.validUntil == null || !it.validUntil.isBefore(now) }
        .groupBy { it.disruptionId }
        .map { (_, versions) -> versions.maxBy { it.version } }
        .sortedWith(disruptionPriorityComparator)
        .distinctBy { it.message.header to it.message.details }
