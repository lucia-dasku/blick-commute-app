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
