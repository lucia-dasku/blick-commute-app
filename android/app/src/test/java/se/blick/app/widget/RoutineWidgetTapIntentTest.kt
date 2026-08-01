package se.blick.app.widget

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.MainActivity
import se.blick.app.notification.RoutineNotificationIds

/**
 * Robolectric tests for [routineDetailsTapIntent] — the widget's tap-to-open action reuses the
 * exact same [MainActivity]/[RoutineNotificationIds.EXTRA_ROUTINE_ID] navigation contract
 * [se.blick.app.notification.RoutineNotificationBuilder]'s own `contentIntent` already uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoutineWidgetTapIntentTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `targets MainActivity`() {
        val intent = routineDetailsTapIntent(context, "r1")
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `carries the routine id under the shared notification extra key`() {
        val intent = routineDetailsTapIntent(context, "r-42")
        assertEquals("r-42", intent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `different routine ids produce different extras, never a stale hardcoded one`() {
        val first = routineDetailsTapIntent(context, "r1")
        val second = routineDetailsTapIntent(context, "r2")
        assertEquals("r1", first.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
        assertEquals("r2", second.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `sets FLAG_ACTIVITY_NEW_TASK -- required since a widget click starts the activity outside any existing task`() {
        val intent = routineDetailsTapIntent(context, "r1")
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `sets FLAG_ACTIVITY_SINGLE_TOP -- matches the notification's own contentIntent behaviour`() {
        val intent = routineDetailsTapIntent(context, "r1")
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }
}
