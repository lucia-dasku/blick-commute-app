package se.blick.app.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class BillingEntitlementReducerTest {
    @Test fun `verified purchase grants premium`() {
        assertEquals(EntitlementState.Premium, BillingEntitlementReducer.reduce(
            PurchaseObservation.PURCHASED, VerificationObservation.VERIFIED, false))
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
}
