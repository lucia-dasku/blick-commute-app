package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class DisruptionMessageDto(
    val header: String,
    val details: String,
    val scopeAlias: String? = null,
    val webLink: String? = null,
    val language: String,
)

@Serializable
data class DisruptionPriorityDto(val importance: Int, val influence: Int, val urgency: Int)

@Serializable
data class DisruptionAffectedLineDto(
    val id: Long,
    val designation: String,
    val transportMode: String,
    val name: String? = null,
)

@Serializable
data class DisruptionDto(
    val disruptionId: String,
    val version: Int,
    val createdAt: String,
    val modifiedAt: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val priority: DisruptionPriorityDto,
    val message: DisruptionMessageDto,
    val affectedStopAreas: List<SiteDeviationStopAreaRefDto> = emptyList(),
    val affectedLines: List<DisruptionAffectedLineDto> = emptyList(),
    val affectedModes: List<String> = emptyList(),
    /** A tolerant wire value, deliberately a plain [String] rather than deserializing straight
     * into [se.blick.app.domain.model.DisruptionEffect] — a future backend value this build
     * doesn't yet know about must never fail to parse the whole disruption. Defaults to
     * `"DISRUPTION"` (a recognized value) so an older backend that omits this field entirely
     * maps the same way an unrecognized value would: see
     * [se.blick.app.domain.model.toDisruptionEffect]. Trailing, like
     * [se.blick.app.domain.model.Disruption.effect]'s own matching field. */
    val effect: String = "DISRUPTION",
)

@Serializable
data class DisruptionsResponseDto(
    val fetchedAt: String,
    val disruptions: List<DisruptionDto>,
)
