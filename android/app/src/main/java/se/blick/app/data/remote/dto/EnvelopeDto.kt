package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

/** Mirrors docs/api-contract.md §2 (Response envelope) exactly. */
@Serializable
data class SuccessEnvelopeDto<T>(
    val schemaVersion: Int,
    val data: T,
)
