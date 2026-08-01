package se.blick.app.ui.screens.routinedetails

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plain Robolectric tests for [launchLiveUpdateSettings] (Fix 2 of the review-response pass):
 * previously the tap silently did nothing on an OEM build without the Live Update settings
 * screen, since the [ActivityNotFoundException] catch block was empty. Both the direct-launch
 * and the fallback path are covered here by injecting a fake `startActivity` lambda rather than
 * relying on a real system's intent resolution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LaunchLiveUpdateSettingsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `launches the Live Update settings screen directly when it resolves`() {
        val launched = mutableListOf<Intent>()

        launchLiveUpdateSettings(context) { launched.add(it) }

        assertEquals(1, launched.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, launched.single().action)
    }

    @Test
    fun `falls back to ordinary notification settings when the Live Update screen does not resolve`() {
        val launched = mutableListOf<Intent>()

        launchLiveUpdateSettings(context) { intent ->
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS) {
                throw ActivityNotFoundException()
            }
            launched.add(intent)
        }

        assertEquals(1, launched.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, launched.single().action)
    }
}
