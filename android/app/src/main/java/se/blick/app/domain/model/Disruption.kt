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
)

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
 * multiple scope entries), and the remainder ordered by [disruptionPriorityComparator] — the
 * single ordering both the Routine Details section and the notification's "highest-priority
 * disruption" pick rely on.
 */
fun List<Disruption>.relevantDisruptions(now: Instant): List<Disruption> =
    asSequence()
        .filter { it.validUntil == null || !it.validUntil.isBefore(now) }
        .groupBy { it.disruptionId }
        .map { (_, versions) -> versions.maxBy { it.version } }
        .sortedWith(disruptionPriorityComparator)
