package se.blick.app.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Android-backed [NotificationAvailabilityChecker]. Bound as a `@Singleton` (see
 * `di/NotificationModule.kt`) and shared by [AndroidRoutineNotifier],
 * [se.blick.app.scheduling.RoutineActiveWindowWorker], and the routine details screen so all
 * three read the exact same live state rather than each re-deriving their own snapshot of it.
 *
 * Order matters: the runtime permission (API 33+ only) is checked first since it is the most
 * specific, most actionable reason on the levels where it applies; the app-wide toggle covers
 * every API level (including < 33, where there is no separate runtime permission to check —
 * [NotificationManagerCompat.areNotificationsEnabled] already reflects only the Settings toggle
 * there); the per-channel check runs last and only matters once the two broader checks have
 * already passed.
 */
@Singleton
class AndroidNotificationAvailabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationAvailabilityChecker {

    override fun check(): NotificationAvailability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationAvailability.PermissionMissing
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationAvailability.AppDisabled
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(RoutineNotificationIds.CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return NotificationAvailability.ChannelDisabled
        }
        return NotificationAvailability.Available
    }
}
