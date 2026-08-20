package se.blick.app.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class BillingEntitlementReducerTest {
    @Test fun `verified purchase grants premium`() {
        assertEquals(EntitlementState.Premium, BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.VERIFIED, false))
    }
    @Test fun `restored purchase grants premium only after backend verification`() {
        assertEquals(EntitlementState.Premium, BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.VERIFIED, false))
        assertEquals(EntitlementState.Free, BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.REJECTED, true))
    }
    @Test fun `pending purchase never grants premium`() {
        assertEquals(EntitlementState.Pending, BillingEntitlementReducer.reduce(
            PurchaseObservation.PENDING, VerificationObservation.NOT_NEEDED, true))
    }
    @Test fun `cancellation or restore with no owned purchase is free`() {
        assertEquals(EntitlementState.Free, BillingEntitlementReducer.reduce(
            PurchaseObservation.NONE, VerificationObservation.NOT_NEEDED, true))
    }
    @Test fun `temporary verification failure preserves last verified premium`() {
        assertEquals(EntitlementState.TemporarilyUnavailable(true), BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.UNAVAILABLE, true))
    }
    @Test fun `rejected purchase revokes cached premium`() {
        assertEquals(EntitlementState.Free, BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.REJECTED, true))
    }

    @Test fun `offline premium cache is bounded by server verification time`() {
        val verifiedAt = 1_000_000L
        assertEquals(true, isVerifiedPremiumCacheFresh(true, true, verifiedAt, verifiedAt + PREMIUM_OFFLINE_GRACE_MS))
        assertEquals(false, isVerifiedPremiumCacheFresh(true, true, verifiedAt, verifiedAt + PREMIUM_OFFLINE_GRACE_MS + 1))
        assertEquals(false, isVerifiedPremiumCacheFresh(true, false, verifiedAt, verifiedAt + 1))
        assertEquals(false, isVerifiedPremiumCacheFresh(true, true, 0L, verifiedAt + 1))
    }
}
