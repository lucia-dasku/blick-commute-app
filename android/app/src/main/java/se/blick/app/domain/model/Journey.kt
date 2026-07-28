package se.blick.app.domain.model

/**
 * `state` and `predictionState` are plain strings, not closed enums — see
 * docs/api-contract.md §4 (Cancellation): unfamiliar future values from SL must not
 * break deserialization, they simply pass through unrecognized.
 */
data class Journey(
    val id: Long,
    val state: String,
    val predictionState: String?,
)
