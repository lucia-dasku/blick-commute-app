package se.blick.app.billing

import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.refreshPremiumBeforeRecovery

class PremiumRefreshRecoveryTest {
    @Test
    fun `normal premium refresh completes before startup recovery uses fresh entitlement`() = runTest {
        val repository = fakeRepository { state -> state.value = EntitlementState.Premium }
        var entitlementSeenByRecovery: EntitlementState? = null

        refreshPremiumBeforeRecovery(repository) {
            entitlementSeenByRecovery = repository.entitlement.value
        }

        assertEquals(EntitlementState.Premium, entitlementSeenByRecovery)
    }

    @Test
    fun `startup recovery proceeds after bounded refresh expiry and respects fresh verified premium`() = runTest {
        val verifiedAt = 1_000_000L
        val repository = fakeRepository(
            lastVerifiedPremium = {
                isVerifiedPremiumCacheFresh(
                    hasValue = true,
                    premium = true,
                    verifiedAtMillis = verifiedAt,
                    nowMillis = verifiedAt + PREMIUM_OFFLINE_GRACE_MS,
                )
            },
        ) { awaitCancellation() }
        var entitlementSeenByRecovery: EntitlementState? = null

        refreshPremiumBeforeRecovery(repository) {
            entitlementSeenByRecovery = repository.entitlement.value
        }

        assertEquals(EntitlementState.TemporarilyUnavailable(true), repository.entitlement.value)
        assertEquals(EntitlementState.TemporarilyUnavailable(true), entitlementSeenByRecovery)
        assertTrue(repository.entitlement.value.hasPremiumAccess)
    }

    @Test
    fun `timeout with no verified premium fails closed and startup recovery still proceeds`() = runTest {
        val repository = fakeRepository(lastVerifiedPremium = { false }) { awaitCancellation() }
        var recoveryCalls = 0

        refreshPremiumBeforeRecovery(repository) { recoveryCalls += 1 }

        assertEquals(EntitlementState.TemporarilyUnavailable(false), repository.entitlement.value)
        assertFalse(repository.entitlement.value.hasPremiumAccess)
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun `timeout with stale verified premium cache fails closed beyond grace`() = runTest {
        val verifiedAt = 1_000_000L
        val repository = fakeRepository(
            lastVerifiedPremium = {
                isVerifiedPremiumCacheFresh(
                    hasValue = true,
                    premium = true,
                    verifiedAtMillis = verifiedAt,
                    nowMillis = verifiedAt + PREMIUM_OFFLINE_GRACE_MS + 1L,
                )
            },
        ) { awaitCancellation() }

        repository.refresh()

        assertEquals(EntitlementState.TemporarilyUnavailable(false), repository.entitlement.value)
        assertFalse(repository.entitlement.value.hasPremiumAccess)
    }

    @Test
    fun `genuine parent cancellation propagates without becoming temporarily unavailable`() = runTest {
        val repository = fakeRepository(lastVerifiedPremium = { true }) { awaitCancellation() }
        var recoveryCalled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshPremiumBeforeRecovery(repository) { recoveryCalled = true }
        }

        job.cancel(CancellationException("parent cancelled"))
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(EntitlementState.Loading, repository.entitlement.value)
        assertFalse(recoveryCalled)
    }

    @Test
    fun `ordinary Billing or backend exception preserves cache-aware fail-soft behavior`() = runTest {
        val repository = fakeRepository(lastVerifiedPremium = { true }) {
            throw IllegalStateException("Billing unavailable")
        }

        repository.refresh()

        assertEquals(EntitlementState.TemporarilyUnavailable(true), repository.entitlement.value)
        assertTrue(repository.entitlement.value.hasPremiumAccess)
    }

    @Test
    fun `normal authoritative Free result remains Free before recovery`() = runTest {
        val repository = fakeRepository(lastVerifiedPremium = { true }) { state ->
            state.value = EntitlementState.Free
        }
        var entitlementSeenByRecovery: EntitlementState? = null

        refreshPremiumBeforeRecovery(repository) {
            entitlementSeenByRecovery = repository.entitlement.value
        }

        assertEquals(EntitlementState.Free, entitlementSeenByRecovery)
        assertFalse(repository.entitlement.value.hasPremiumAccess)
    }

    @Test
    fun `foreground recovery cannot be blocked indefinitely by premium refresh`() = runTest {
        val repository = fakeRepository(lastVerifiedPremium = { false }) { awaitCancellation() }
        var foregroundCalls = 0

        refreshPremiumBeforeRecovery(repository) { foregroundCalls += 1 }

        assertEquals(EntitlementState.TemporarilyUnavailable(false), repository.entitlement.value)
        assertEquals(1, foregroundCalls)
    }

    @Test
    fun `manual restore times out fail-soft and remains retryable`() = runTest {
        var billingUnavailable = true
        val repository = fakeRepository(lastVerifiedPremium = { false }) { state ->
            if (billingUnavailable) awaitCancellation()
            state.value = EntitlementState.Premium
        }

        repository.restore()
        assertEquals(EntitlementState.TemporarilyUnavailable(false), repository.entitlement.value)

        billingUnavailable = false
        repository.restore()
        assertEquals(EntitlementState.Premium, repository.entitlement.value)
    }

    private fun fakeRepository(
        lastVerifiedPremium: () -> Boolean = { false },
        refresh: suspend (MutableStateFlow<EntitlementState>) -> Unit,
    ): PremiumEntitlementRepository = FakeBoundedPremiumEntitlementRepository(
        lastVerifiedPremium = lastVerifiedPremium,
        refreshOperation = refresh,
    )

    private class FakeBoundedPremiumEntitlementRepository(
        private val lastVerifiedPremium: () -> Boolean,
        private val refreshOperation: suspend (MutableStateFlow<EntitlementState>) -> Unit,
    ) : PremiumEntitlementRepository {
        private val state = MutableStateFlow<EntitlementState>(EntitlementState.Loading)
        override val entitlement: StateFlow<EntitlementState> = state
        override val localizedPrice: StateFlow<String?> = MutableStateFlow(null)

        override suspend fun refresh() {
            runBoundedPremiumRefresh(
                lastVerifiedPremium = lastVerifiedPremium,
                updateEntitlement = { state.value = it },
            ) {
                refreshOperation(state)
            }
        }

        override suspend fun restore() = refresh()
        override fun launchPurchase(activity: Activity) = Unit
    }
}
