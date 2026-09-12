package se.blick.app.billing

import android.app.Application
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.ReviewerAccessValidationResponseDto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EffectivePremiumEntitlementStateTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(PREMIUM_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `concurrent reviewer deactivation and Play no-access update cannot republish Premium`() {
        val state = EffectivePremiumEntitlementState(
            initialPlayEntitlement = EntitlementState.Free,
            initialReviewerAccessActive = true,
            initialDebugOverrideEnabled = false,
        )

        runSerializedSourceUpdates(
            state = state,
            firstUpdate = { it.copy(playEntitlement = EntitlementState.Free) },
            concurrentUpdate = { state.synchronizeReviewerAccess { false } },
        )

        assertFalse(state.reviewerAccessActive.value)
        assertFalse(state.entitlement.value.hasPremiumAccess)
        assertEquals(EntitlementState.Free, state.entitlement.value)
    }

    @Test
    fun `concurrent reviewer activation and Play update cannot leave reviewer gated as Free`() {
        val state = EffectivePremiumEntitlementState(
            initialPlayEntitlement = EntitlementState.Free,
            initialReviewerAccessActive = false,
            initialDebugOverrideEnabled = false,
        )

        runSerializedSourceUpdates(
            state = state,
            firstUpdate = { it.copy(playEntitlement = EntitlementState.Pending) },
            concurrentUpdate = { state.synchronizeReviewerAccess { true } },
        )

        assertTrue(state.reviewerAccessActive.value)
        assertEquals(EntitlementState.Premium, state.entitlement.value)
    }

    @Test
    fun `concurrent reviewer deactivation preserves genuine Play Premium`() {
        val state = EffectivePremiumEntitlementState(
            initialPlayEntitlement = EntitlementState.Free,
            initialReviewerAccessActive = true,
            initialDebugOverrideEnabled = false,
        )

        runSerializedSourceUpdates(
            state = state,
            firstUpdate = { it.copy(reviewerAccessActive = false) },
            concurrentUpdate = { state.updatePlayEntitlement(EntitlementState.Premium) },
        )

        assertFalse(state.reviewerAccessActive.value)
        assertEquals(EntitlementState.Premium, state.entitlement.value)
    }

    @Test
    fun `concurrent debug disable and Play update cannot leave stale Premium`() {
        val state = EffectivePremiumEntitlementState(
            initialPlayEntitlement = EntitlementState.Free,
            initialReviewerAccessActive = false,
            initialDebugOverrideEnabled = true,
        )

        runSerializedSourceUpdates(
            state = state,
            firstUpdate = { it.copy(playEntitlement = EntitlementState.Free) },
            concurrentUpdate = { state.updateDebugOverride(false) },
        )

        assertFalse(state.debugOverrideEnabled.value)
        assertFalse(state.entitlement.value.hasPremiumAccess)
    }

    @Test
    fun `real repository publishes activation and Play updates synchronously`() = runTest {
        val validationStarted = CountDownLatch(1)
        val releaseValidation = CountDownLatch(1)
        val apiClient = mockk<BlickApiClient>()
        coEvery { apiClient.validateReviewerAccess(any()) } coAnswers {
            validationStarted.countDown()
            check(releaseValidation.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            ReviewerAccessValidationResponseDto(authorized = true)
        }
        val controller = ReviewerAccessController(apiClient, FakeReviewerAccessGrantStore())
        val repository = GooglePlayPremiumEntitlementRepository(context, apiClient, controller)
        val activation = async(Dispatchers.Default) {
            repository.activateReviewerAccess("r".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH))
        }

        try {
            assertTrue(validationStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            repository.onPurchasesUpdated(unavailableBillingResult(), null)
            assertFalse(repository.entitlement.value.hasPremiumAccess)

            releaseValidation.countDown()
            assertEquals(ReviewerAccessActivationResult.Activated, activation.await())

            assertTrue(repository.reviewerAccessActive.value)
            assertEquals(EntitlementState.Premium, repository.entitlement.value)
        } finally {
            releaseValidation.countDown()
            activation.cancelAndJoin()
        }
    }

    @Test
    fun `real repository deactivation reveals concurrent Play no-access update before returning`() = runTest {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val apiClient = mockk<BlickApiClient>()
        val store = FakeReviewerAccessGrantStore(initialActive = true) { active ->
            if (!active) {
                writeStarted.countDown()
                check(releaseWrite.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
        val repository = GooglePlayPremiumEntitlementRepository(
            context,
            apiClient,
            ReviewerAccessController(apiClient, store),
        )
        val deactivation = async(Dispatchers.Default) { repository.deactivateReviewerAccess() }

        try {
            assertTrue(writeStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            repository.onPurchasesUpdated(unavailableBillingResult(), null)
            assertEquals(EntitlementState.Premium, repository.entitlement.value)

            releaseWrite.countDown()
            deactivation.await()

            assertFalse(repository.reviewerAccessActive.value)
            assertFalse(repository.entitlement.value.hasPremiumAccess)
            assertEquals(
                EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = false),
                repository.entitlement.value,
            )
        } finally {
            releaseWrite.countDown()
            deactivation.cancelAndJoin()
        }
    }

    @Test
    fun `real repository deactivation preserves concurrent cached Play Premium`() = runTest {
        context.getSharedPreferences(PREMIUM_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("last_verified_premium", true)
            .putBoolean("has_verified_entitlement", true)
            .putLong("last_google_verified_at", System.currentTimeMillis())
            .commit()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val apiClient = mockk<BlickApiClient>()
        val store = FakeReviewerAccessGrantStore(initialActive = true) { active ->
            if (!active) {
                writeStarted.countDown()
                check(releaseWrite.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
        val repository = GooglePlayPremiumEntitlementRepository(
            context,
            apiClient,
            ReviewerAccessController(apiClient, store),
        )
        val deactivation = async(Dispatchers.Default) { repository.deactivateReviewerAccess() }

        try {
            assertTrue(writeStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            repository.onPurchasesUpdated(unavailableBillingResult(), null)
            releaseWrite.countDown()
            deactivation.await()

            assertFalse(repository.reviewerAccessActive.value)
            assertTrue(repository.entitlement.value.hasPremiumAccess)
            assertEquals(
                EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = true),
                repository.entitlement.value,
            )
        } finally {
            releaseWrite.countDown()
            deactivation.cancelAndJoin()
        }
    }

    private fun runSerializedSourceUpdates(
        state: EffectivePremiumEntitlementState,
        firstUpdate: (EffectivePremiumEntitlementSources) -> EffectivePremiumEntitlementSources,
        concurrentUpdate: () -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(2)
        val firstSnapshotCaptured = CountDownLatch(1)
        val releaseFirstUpdate = CountDownLatch(1)
        val concurrentUpdateReady = CyclicBarrier(2)
        val first = executor.submit {
            state.updateSources { current ->
                firstSnapshotCaptured.countDown()
                check(releaseFirstUpdate.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                firstUpdate(current)
            }
        }

        try {
            assertTrue(firstSnapshotCaptured.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val concurrent = executor.submit {
                concurrentUpdateReady.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                concurrentUpdate()
            }
            concurrentUpdateReady.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val completedBeforeFirstPublication = try {
                concurrent.get(SERIALIZATION_PROOF_MILLIS, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }
            releaseFirstUpdate.countDown()
            first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            concurrent.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertFalse(
                "A competing source update completed after the first snapshot but before its publication",
                completedBeforeFirstPublication,
            )
        } finally {
            releaseFirstUpdate.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun unavailableBillingResult(): BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.ERROR)
        .setDebugMessage("test-only unavailable billing result")
        .build()

    private class FakeReviewerAccessGrantStore(
        initialActive: Boolean = false,
        private val beforeWrite: (Boolean) -> Unit = {},
    ) : ReviewerAccessGrantStore {
        @Volatile
        private var active = initialActive

        override fun readActive(): Boolean = active

        override fun writeActive(active: Boolean) {
            beforeWrite(active)
            this.active = active
        }
    }

    private companion object {
        const val PREMIUM_PREFERENCES = "premium_entitlement_cache"
        const val TEST_TIMEOUT_SECONDS = 5L
        const val SERIALIZATION_PROOF_MILLIS = 500L
    }
}
