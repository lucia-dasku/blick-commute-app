package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReviewerAccessValidationRequestDto(
    val code: String,
)

@Serializable
data class ReviewerAccessValidationResponseDto(
    val authorized: Boolean,
)
