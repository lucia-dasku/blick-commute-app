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

/**
 * The small, shared shape [se.blick.app.notification.RoutineNotificationMapper],
 * [se.blick.app.widget.RoutineWidgetMapper], and Routine Details' own disruption cards actually
 * consume — deliberately NOT a full [Disruption]: an exact-destination journey's own
 * [JourneyDisruptionNotice] has no disruption id, version, SL Deviations priority, affected
 * stop areas, or validity range, and constructing a fake [Disruption] with invented values for
 * those just to satisfy a mapper that only ever reads `message.header`/`message.details`/`effect`
 * would be worse than a small, purpose-built type both sources can produce honestly.
 *
 * [Disruption]'s own `message.header`/`message.details`/`effect` map onto [headline]/[details]/
 * [effect] one-for-one for the `LINE_DIRECTION` path (see [toPresentation]); a
 * [JourneyDisruptionNotice]'s own `text`/`effect` map onto [headline]/[effect] for the
 * `EXACT_DESTINATION` path, with [details] left null (Journey Planner notices have no separate
 * longer body the way an SL Deviations message does).
 */
data class DisruptionPresentation(
    val headline: String,
    val details: String?,
    val effect: DisruptionEffect,
)

/** Adapts a real SL Deviations [Disruption] (the `LINE_DIRECTION` path) to the shared
 * [DisruptionPresentation] shape every disruption consumer now reads from — see that type's own
 * doc. */
fun Disruption.toPresentation(): DisruptionPresentation =
    DisruptionPresentation(headline = message.header, details = message.details, effect = effect)

/**
 * Higher [DisruptionPriority.importance] first, then [DisruptionPriority.influence], then
 * [DisruptionPriority.urgency] — upstream (SL) documents these three fields only as sort
 * hints, with no defined combined ordering or stated direction (see
 * docs/api-contract.md §3.3), so "higher number = more important" is this app's own
 * assumption, applied consistently everywhere a single ranking is needed (the Routine
 * Details disruptions section, and picking the one disruption shown in the notification).
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
