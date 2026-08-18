package se.blick.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PurchaseVerificationRequestDto(
    val productId: String,
    val purchaseToken: String,
)

@Serializable
data class PurchaseVerificationResponseDto(
    val productId: String,
    val verified: Boolean,
    val state: String,
    val verifiedAt: String,
)
