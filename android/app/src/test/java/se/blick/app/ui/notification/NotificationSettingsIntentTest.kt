package se.blick.app.ui.notification

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationSettingsIntentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `Live Updates opens ordinary notification settings below Android 16`() {
        val launched = mutableListOf<Intent>()

        launchLiveUpdateSettings(context, sdkInt = 35, startActivity = launched::add)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, launched.single().action)
    }

    @Test
    fun `Live Updates opens dedicated promotion settings on Android 16`() {
        val launched = mutableListOf<Intent>()

        launchLiveUpdateSettings(context, sdkInt = 36, startActivity = launched::add)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, launched.single().action)
    }
}
