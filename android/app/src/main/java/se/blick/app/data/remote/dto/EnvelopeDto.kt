package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

/** Mirrors docs/api-contract.md §2 (Response envelope) exactly. */
@Serializable
data class SuccessEnvelopeDto<T>(
    val schemaVersion: Int,
    val data: T,
)

@Serializable
data class ErrorEnvelopeDto(
    val schemaVersion: Int,
    val error: ErrorBodyDto,
)

@Serializable
data class ErrorBodyDto(
    val code: String,
    val message: String,
)
