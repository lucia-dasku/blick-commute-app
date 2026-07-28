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
)

@Serializable
data class DisruptionsResponseDto(
    val fetchedAt: String,
    val disruptions: List<DisruptionDto>,
)
