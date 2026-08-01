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

/** The system intent to open this app's own Live Update / promoted-notification settings
 * screen (Android 16+ only — see
 * [se.blick.app.notification.PromotedNotificationChecker]'s own doc on why "eligible" is not
 * the same as "will actually render"). Only ever launched from behind a
 * [android.content.pm.PackageManager] resolvability guard at the call site — this action
 * (`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`) may not resolve on pre-Android-16
 * devices or on an OEM build that doesn't ship this settings screen, and launching an intent
 * with no matching activity throws [android.content.ActivityNotFoundException]. */
fun promotedNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
