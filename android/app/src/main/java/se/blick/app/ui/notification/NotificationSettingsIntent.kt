package se.blick.app.ui.notification

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The standard system intent to open this app's own notification settings screen — used by
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen]'s notification-status hint so a
 * person who denied (or later disabled) notifications has a direct route back to fix it,
 * rather than only ever being asked once via [rememberNotificationPermissionGate] and having no
 * other way back in from inside the app. */
fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
