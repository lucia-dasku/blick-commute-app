package se.blick.app.ui.screens.routinedetails

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for [shouldOfferLiveUpdateSettingsLink] — deliberately not Robolectric, since
 * this project's Robolectric pin (`@Config(sdk = [34])`, see `libs.versions.toml`'s
 * `robolectric` entry) can't exercise `Build.VERSION_CODES.BAKLAVA` (36) directly. [sdkInt] is
 * a plain parameter here specifically so this real bug — the Live Update settings row used to
 * render on every Android version, including ones below 16 where the target settings screen
 * can't exist and the link silently does nothing — is covered without that constraint.
 */
class ShouldOfferLiveUpdateSettingsLinkTest {

    @Test
    fun `hidden below Android 16 even when not yet promotable`() {
        assertFalse(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = false, sdkInt = 33))
        assertFalse(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = false, sdkInt = 34))
        assertFalse(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = false, sdkInt = 35))
    }

    @Test
    fun `shown on Android 16 and above when not yet promotable`() {
        assertTrue(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = false, sdkInt = 36))
        assertTrue(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = false, sdkInt = 37))
    }

    @Test
    fun `hidden once already promotable, regardless of SDK level`() {
        assertFalse(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = true, sdkInt = 36))
        assertFalse(shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable = true, sdkInt = 37))
    }
}
