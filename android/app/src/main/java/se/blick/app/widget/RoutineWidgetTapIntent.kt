package se.blick.app.widget

import android.content.Context
import android.content.Intent
import se.blick.app.MainActivity
import se.blick.app.notification.RoutineNotificationIds

/**
 * The same routine-details navigation contract [se.blick.app.notification.RoutineNotificationBuilder.contentIntent]
 * already uses — [RoutineNotificationIds.EXTRA_ROUTINE_ID] read by
 * [se.blick.app.notification.NotificationIntentCoordinator.consumeRoutineId] in
 * [MainActivity.onCreate]/`onNewIntent`. [Intent.FLAG_ACTIVITY_NEW_TASK] is required here (unlike
 * the notification's own [android.app.PendingIntent], which always launches from a non-Activity
 * context) since a Glance widget click starts the activity directly from the launcher's task,
 * not from an existing Blick task.
 */
internal fun routineDetailsTapIntent(context: Context, routineId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID, routineId)
    }
