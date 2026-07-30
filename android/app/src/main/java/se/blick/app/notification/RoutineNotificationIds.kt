package se.blick.app.notification

/**
 * Stable identifiers for "the one ongoing commute notification" — shared by
 * [RoutineNotificationBuilder] (channel creation + content) and [AndroidRoutineNotifier]
 * (posting/cancelling), but never exposed through the [RoutineNotifier] interface itself, so
 * callers of that interface cannot depend on — or accidentally bypass — the fact that there
 * is only ever one channel and one notification id.
 */
internal object RoutineNotificationIds {
    const val CHANNEL_ID = "routine_commute_channel"
    const val NOTIFICATION_ID = 1001

    /** Intent extra key carrying the routine id to reopen — read by [se.blick.app.MainActivity]. */
    const val EXTRA_ROUTINE_ID = "se.blick.app.EXTRA_ROUTINE_ID"

    /** A single stable request code: there is only ever one notification, so only one
     * content [android.app.PendingIntent] ever needs to exist. Combined with
     * `FLAG_UPDATE_CURRENT`, rebuilding it on every [RoutineNotifier.showOrUpdate] call
     * refreshes its routine-id extra in place rather than accumulating separate PendingIntents. */
    const val CONTENT_INTENT_REQUEST_CODE = 1001
}
