package se.blick.app

import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.billing.DEBUG_PREMIUM_OVERRIDE_AVAILABLE
import se.blick.app.ui.screens.routinedetails.DEBUG_NOTIFICATION_TOOLS_AVAILABLE

class DeveloperFeaturesDebugTest {
    @Test
    fun `debug build keeps device testing controls available`() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(DEBUG_PREMIUM_OVERRIDE_AVAILABLE)
        assertTrue(DEBUG_NOTIFICATION_TOOLS_AVAILABLE)
    }
}
