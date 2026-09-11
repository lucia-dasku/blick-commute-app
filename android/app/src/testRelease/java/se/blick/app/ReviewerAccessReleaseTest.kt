package se.blick.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.billing.DEBUG_PREMIUM_OVERRIDE_AVAILABLE

class ReviewerAccessReleaseTest {
    @Test
    fun `release keeps developer override unavailable and reviewer credential out of BuildConfig`() {
        assertFalse(BuildConfig.DEBUG)
        assertFalse(DEBUG_PREMIUM_OVERRIDE_AVAILABLE)
        assertTrue(
            BuildConfig::class.java.declaredFields.none {
                it.name.contains("REVIEWER", ignoreCase = true)
            },
        )
    }
}
