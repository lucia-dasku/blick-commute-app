package se.blick.app.notification

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete, Android-backed [RoutineNotifier]. Owns the stable notification id
 * ([RoutineNotificationIds.NOTIFICATION_ID]) entirely — every [showOrUpdate] call posts to
 * that same id (updating the existing notification in place, never creating a second one),
 * and [remove] only ever cancels that id.
 *
 * Bound as a `@Singleton` via `di/NotificationModule.kt` — there must only ever be one
 * instance, matching there only ever being one notification.
 */
@Singleton
class AndroidRoutineNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationBuilder: RoutineNotificationBuilder,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
) : RoutineNotifier {

    // Lint's MissingPermission check flags the notify() call below (POST_NOTIFICATIONS is a
    // conditional runtime permission on API 33+) because it doesn't recognize
    // notificationAvailabilityChecker.check() below as an equivalent guard -- that check
    // already folds the runtime permission state into its own non-Available result on API
    // 33+ (see NotificationAvailability's doc comment), so by the time this method reaches
    // notify(), the permission state has already been accounted for. This suppression covers
    // exactly that known false positive, not a real gap.
    @SuppressLint("MissingPermission")
    override fun showOrUpdate(model: RoutineNotificationModel): NotificationPostResult {
        if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) {
            return NotificationPostResult.NotificationsDisabled
        }

        // Both construction (notificationBuilder.build) and posting are inside this single
        // guarded block: build() is not a trivial no-throw operation (it does string-resource
        // lookups and PendingIntent construction), so a failure there must also be reported
        // as Failed rather than propagating as an uncaught exception to the caller (the
        // debug trigger, and later any real refresh path, must never crash from this).
        return runCatching {
            val notification = notificationBuilder.build(model)
            NotificationManagerCompat.from(context).notify(RoutineNotificationIds.NOTIFICATION_ID, notification)
        }.fold(
            onSuccess = { NotificationPostResult.Posted },
            onFailure = { NotificationPostResult.Failed },
        )
    }

    override fun remove() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(RoutineNotificationIds.NOTIFICATION_ID)
        }
    }
}
