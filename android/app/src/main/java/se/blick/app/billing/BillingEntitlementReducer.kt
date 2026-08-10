package se.blick.app.billing

enum class PurchaseObservation { NONE, PENDING, PURCHASED }
enum class VerificationObservation { NOT_NEEDED, VERIFIED, REJECTED, UNAVAILABLE }

object BillingEntitlementReducer {
    fun reduce(
        purchase: PurchaseObservation,
        verification: VerificationObservation,
        lastVerifiedPremium: Boolean,
    ): EntitlementState = when (purchase) {
        PurchaseObservation.NONE -> EntitlementState.Free
        PurchaseObservation.PENDING -> EntitlementState.Pending
        PurchaseObservation.PURCHASED -> when (verification) {
            VerificationObservation.VERIFIED -> EntitlementState.Premium
            VerificationObservation.REJECTED, VerificationObservation.NOT_NEEDED -> EntitlementState.Free
            VerificationObservation.UNAVAILABLE -> EntitlementState.TemporarilyUnavailable(lastVerifiedPremium)
        }
    }
}
