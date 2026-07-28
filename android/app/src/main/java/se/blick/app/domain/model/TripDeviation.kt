package se.blick.app.domain.model

data class TripDeviation(
    val importanceLevel: Int,
    val consequence: String,
    val message: String,
)
