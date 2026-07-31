package se.blick.app.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Separate from [NotificationAvailabilityChecker]: that interface answers "can Blick deliver a
 * visible notification at all," which [RoutineNotificationBuilder]'s notifications already
 * satisfy on every supported Android version regardless of this check. This one instead answers
 * "will the system actually promote that same notification to the prominent lock-screen Live
 * Update surface (Samsung's Now Bar where supported) rather than only showing it in the
 * notification shade" — an enhancement layered on top, never a blocking condition, since
 * [RoutineNotificationBuilder.build] already produces a perfectly valid plain ongoing
 * notification either way (see that class's own doc). Android 16+ only; always `false` below
 * that, regardless of permission/settings state.
 */
interface PromotedNotificationChecker {
    fun isPromotable(): Boolean
}

@Singleton
class AndroidPromotedNotificationChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : PromotedNotificationChecker {
    override fun isPromotable(): Boolean =
        NotificationManagerCompat.from(context).canPostPromotedNotifications()
}
