package se.blick.app.notification

import android.content.Intent
import se.blick.app.domain.model.ActiveCommuteSource
import se.blick.app.domain.model.ActiveCommuteSourceType
import se.blick.app.domain.model.activeCommuteSource

/**
 * Ensures a notification-tap [Intent] is only ever processed once.
 *
 * `MainActivity` reads [RoutineNotificationIds.EXTRA_ROUTINE_ID] from both `onCreate` (cold
 * start — the routine id arrives via the launching `Activity.getIntent()`) and `onNewIntent`
 * (warm/hot start). Both of those ultimately read from the SAME stored `Intent` instance:
 * `Activity.getIntent()`/`setIntent()` retain the same object across an in-process Activity
 * recreation (e.g. a screen rotation), so if the extra were only ever *read* and never
 * *removed*, a later recreation's `onCreate` would observe the exact same extra again and
 * re-navigate to a routine the person may have already navigated away from — this is the bug
 * this coordinator fixes.
 *
 * [consumeRoutineId] both reads and removes the extra from the same `Intent` object in one
 * step, so any later read of that same stored intent (whether via a genuine second tap's
 * `onNewIntent`, which always supplies a fresh `Intent`, or via `onCreate` after an in-process
 * recreation, which reuses the same, now-stripped `Intent`) can never observe an
 * already-consumed routine id again.
 *
 * Kept as a small, dependency-free object (rather than inline in `MainActivity`) specifically
 * so this one-time-consumption behaviour is directly unit-testable against a real
 * [Intent]/[android.os.Bundle] (see `NotificationIntentCoordinatorTest`, Robolectric-backed)
 * without needing a full Activity, Hilt component, or Compose Navigation test harness.
 */
object NotificationIntentCoordinator {

    /** Consumes the explicit source type/id carried by the single active commute notification. */
    fun consumeActiveCommuteSource(intent: Intent): ActiveCommuteSource? {
        val encodedType = intent.getStringExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_TYPE)
        val sourceId = intent.getStringExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_ID)
        intent.removeExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_TYPE)
        intent.removeExtra(RoutineNotificationIds.EXTRA_ACTIVE_COMMUTE_SOURCE_ID)
        if (encodedType == null || sourceId == null) return null
        val type = runCatching { ActiveCommuteSourceType.valueOf(encodedType) }.getOrNull() ?: return null
        return activeCommuteSource(type, sourceId)
    }

    /**
     * Returns the routine id carried by [intent] (or null if it carries none) and removes it
     * from [intent] in the same call. Safe to call repeatedly on the same [Intent] instance:
     * every call after the first returns null, since the extra is gone.
     */
    fun consumeRoutineId(intent: Intent): String? {
        val routineId = intent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID) ?: return null
        intent.removeExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID)
        return routineId
    }
}
