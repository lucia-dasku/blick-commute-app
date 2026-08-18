package se.blick.app.ui.screens.about

import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.R

class LiveUpdatesStatusTest {
    @Test
    fun `below Android 16 shows requirement instead of Off`() {
        assertEquals(
            R.string.settings_live_updates_requires_android_16,
            liveUpdatesLabelRes(enabled = false, sdkInt = 35),
        )
    }

    @Test
    fun `Android 16 and above show platform eligibility as On or Off`() {
        assertEquals(R.string.settings_notifications_on, liveUpdatesLabelRes(enabled = true, sdkInt = 36))
        assertEquals(R.string.settings_notifications_off, liveUpdatesLabelRes(enabled = false, sdkInt = 36))
    }
}
