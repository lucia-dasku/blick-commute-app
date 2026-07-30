package se.blick.app.notification

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests against a REAL [Intent]/[android.os.Bundle] — not a mock — since
 * the bug this coordinator fixes (`MainActivity` re-observing an already-handled notification
 * routine id after an in-process recreation) is specifically about how `Intent` extras are
 * actually stored and mutated, which a plain JVM stub (`unitTests.isReturnDefaultValues`)
 * cannot faithfully reproduce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationIntentCoordinatorTest {

    @Test
    fun `consumeRoutineId returns the routine id extra`() {
        val intent = Intent().putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, "r1")
        assertEquals("r1", NotificationIntentCoordinator.consumeRoutineId(intent))
    }

    @Test
    fun `consumeRoutineId removes the extra from the same Intent instance`() {
        val intent = Intent().putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, "r1")

        NotificationIntentCoordinator.consumeRoutineId(intent)

        assertNull(intent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID))
    }

    @Test
    fun `a second consume call on the same intent returns null -- simulating an Activity recreation replaying the stored intent`() {
        val intent = Intent().putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, "r1")

        val first = NotificationIntentCoordinator.consumeRoutineId(intent)
        val second = NotificationIntentCoordinator.consumeRoutineId(intent)

        assertEquals("r1", first)
        assertNull(second)
    }

    @Test
    fun `an intent with no routine id extra yields null and is left otherwise unaffected`() {
        val intent = Intent().putExtra("some_other_extra", "value")

        assertNull(NotificationIntentCoordinator.consumeRoutineId(intent))
        assertEquals("value", intent.getStringExtra("some_other_extra"))
    }

    @Test
    fun `a later intent with a different routine id is processed independently`() {
        val firstTap = Intent().putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, "r1")
        val secondTap = Intent().putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, "r2")

        val first = NotificationIntentCoordinator.consumeRoutineId(firstTap)
        val second = NotificationIntentCoordinator.consumeRoutineId(secondTap)

        assertEquals("r1", first)
        assertEquals("r2", second)
        // Consuming the second (different) intent must not affect the first, already-consumed one.
        assertNull(NotificationIntentCoordinator.consumeRoutineId(firstTap))
    }
}
