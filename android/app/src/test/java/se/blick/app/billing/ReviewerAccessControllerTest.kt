package se.blick.app.billing

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.ReviewerAccessValidationResponseDto

class ReviewerAccessControllerTest {
    private val apiClient = mockk<BlickApiClient>()

    @Test
    fun `authorized trimmed code persists a grant without storing the credential`() = runTest {
        val code = "v".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH)
        val store = FakeReviewerAccessGrantStore()
        coEvery { apiClient.validateReviewerAccess(code) } returns
            ReviewerAccessValidationResponseDto(authorized = true)
        val controller = ReviewerAccessController(apiClient, store)

        val result = controller.activate("  $code\n")

        assertEquals(ReviewerAccessActivationResult.Activated, result)
        assertTrue(controller.active.value)
        assertEquals(listOf(true), store.writes)
        coVerify(exactly = 1) { apiClient.validateReviewerAccess(code) }
    }

    @Test
    fun `blank short and oversized codes fail locally without a request or grant`() = runTest {
        val store = FakeReviewerAccessGrantStore()
        val controller = ReviewerAccessController(apiClient, store)

        assertEquals(ReviewerAccessActivationResult.InvalidCode, controller.activate(" \n\t "))
        assertEquals(
            ReviewerAccessActivationResult.InvalidCode,
            controller.activate("x".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH - 1)),
        )
        assertEquals(
            ReviewerAccessActivationResult.InvalidCode,
            controller.activate("x".repeat(REVIEWER_ACCESS_CODE_MAX_LENGTH + 1)),
        )

        assertFalse(controller.active.value)
        assertTrue(store.writes.isEmpty())
        coVerify(exactly = 0) { apiClient.validateReviewerAccess(any()) }
    }

    @Test
    fun `invalid credential never creates a grant and leaves an existing grant unchanged`() = runTest {
        coEvery { apiClient.validateReviewerAccess(any()) } returns
            ReviewerAccessValidationResponseDto(authorized = false)
        val inactiveStore = FakeReviewerAccessGrantStore()
        val inactive = ReviewerAccessController(apiClient, inactiveStore)
        val activeStore = FakeReviewerAccessGrantStore(initialActive = true)
        val active = ReviewerAccessController(apiClient, activeStore)

        val candidate = "w".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH)
        assertEquals(ReviewerAccessActivationResult.InvalidCode, inactive.activate(candidate))
        assertEquals(ReviewerAccessActivationResult.InvalidCode, active.activate(candidate))

        assertFalse(inactive.active.value)
        assertTrue(active.active.value)
        assertTrue(inactiveStore.writes.isEmpty())
        assertTrue(activeStore.writes.isEmpty())
    }

    @Test
    fun `transport rate limit and server failures never create or clear a grant`() = runTest {
        listOf(
            IOException("transport failure"),
            IllegalStateException("rate limited"),
            IllegalArgumentException("server failure"),
        ).forEach { failure ->
            coEvery { apiClient.validateReviewerAccess(any()) } throws failure
            val inactive = ReviewerAccessController(apiClient, FakeReviewerAccessGrantStore())
            val active = ReviewerAccessController(apiClient, FakeReviewerAccessGrantStore(initialActive = true))
            val candidate = "c".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH)

            assertEquals(
                ReviewerAccessActivationResult.TemporarilyUnavailable,
                inactive.activate(candidate),
            )
            assertEquals(
                ReviewerAccessActivationResult.TemporarilyUnavailable,
                active.activate(candidate),
            )
            assertFalse(inactive.active.value)
            assertTrue(active.active.value)
        }
    }

    @Test
    fun `bounded validation timeout never grants access`() = runTest {
        coEvery { apiClient.validateReviewerAccess(any()) } coAnswers { awaitCancellation() }
        val store = FakeReviewerAccessGrantStore()
        val controller = ReviewerAccessController(apiClient, store)

        val result = controller.activate("c".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH))

        assertEquals(ReviewerAccessActivationResult.TemporarilyUnavailable, result)
        assertFalse(controller.active.value)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `same authorized code can activate independent installations`() = runTest {
        val code = "r".repeat(REVIEWER_ACCESS_CODE_MIN_LENGTH)
        coEvery { apiClient.validateReviewerAccess(code) } returns
            ReviewerAccessValidationResponseDto(authorized = true)
        val first = ReviewerAccessController(apiClient, FakeReviewerAccessGrantStore())
        val second = ReviewerAccessController(apiClient, FakeReviewerAccessGrantStore())

        assertEquals(ReviewerAccessActivationResult.Activated, first.activate(code))
        assertEquals(ReviewerAccessActivationResult.Activated, second.activate(code))

        assertTrue(first.active.value)
        assertTrue(second.active.value)
        coVerify(exactly = 2) { apiClient.validateReviewerAccess(code) }
    }

    @Test
    fun `deactivation removes only the reviewer grant`() = runTest {
        val store = FakeReviewerAccessGrantStore(initialActive = true)
        val controller = ReviewerAccessController(apiClient, store)

        controller.deactivate()

        assertFalse(controller.active.value)
        assertEquals(listOf(false), store.writes)
    }

    private class FakeReviewerAccessGrantStore(
        initialActive: Boolean = false,
    ) : ReviewerAccessGrantStore {
        private var active = initialActive
        val writes = mutableListOf<Boolean>()

        override fun readActive(): Boolean = active

        override fun writeActive(active: Boolean) {
            this.active = active
            writes += active
        }
    }
}
