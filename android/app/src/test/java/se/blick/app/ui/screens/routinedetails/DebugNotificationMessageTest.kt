package se.blick.app.ui.screens.routinedetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.R
import se.blick.app.notification.NotificationPostResult

/**
 * Narrow test seam for [toDebugMessage] (Fix 3): the debug UI's result-to-text mapping must
 * never translate [NotificationPostResult.NotificationsDisabled] or
 * [NotificationPostResult.Failed] (or a null/no-routine-loaded result) into the same message
 * as an actual [NotificationPostResult.Posted] — that was the exact bug (the debug UI always
 * showed "Test notification posted" regardless of what the notifier actually did).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DebugNotificationMessageTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Test
    fun `Posted maps to the posted message`() {
        assertEquals(
            context.getString(R.string.debug_notification_posted),
            NotificationPostResult.Posted.toDebugMessage(context),
        )
    }

    @Test
    fun `NotificationsDisabled does not map to the posted message`() {
        val message = NotificationPostResult.NotificationsDisabled.toDebugMessage(context)
        assertEquals(context.getString(R.string.debug_notification_disabled), message)
        assertNotEquals(context.getString(R.string.debug_notification_posted), message)
    }

    @Test
    fun `Failed does not map to the posted message`() {
        val message = NotificationPostResult.Failed.toDebugMessage(context)
        assertEquals(context.getString(R.string.debug_notification_failed), message)
        assertNotEquals(context.getString(R.string.debug_notification_posted), message)
    }

    @Test
    fun `a null result (no routine loaded) does not map to the posted message`() {
        val message: NotificationPostResult? = null
        val resolved = message.toDebugMessage(context)
        assertEquals(context.getString(R.string.debug_notification_failed), resolved)
        assertNotEquals(context.getString(R.string.debug_notification_posted), resolved)
    }
}
