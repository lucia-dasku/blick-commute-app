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
 * "is Blick currently *eligible* to have a notification promoted to the lock-screen Live Update
 * surface" — an enhancement layered on top, never a blocking condition, since
 * [RoutineNotificationBuilder.build] already produces a perfectly valid plain ongoing
 * notification either way (see that class's own doc). Android 16+ only; always `false` below
 * that, regardless of permission/settings state.
 *
 * Eligible is not the same as "will actually be promoted": [isPromotable] reflects
 * [NotificationManagerCompat.canPostPromotedNotifications], the platform's own eligibility
 * signal (Android version, the runtime permission, and the user's own per-app Live Update
 * setting) — but an OEM can impose additional criteria on top that this call has no visibility
 * into. Confirmed on a real Samsung Galaxy S23 Ultra: even when the platform request is well
 * formed, One UI's Now Bar can still decline to render it behind a separate,
 * Samsung-controlled restriction (see `android/README.md`'s Known Limitations) — this checker
 * cannot detect that case, and neither can any other API available to a third-party app. Treat
 * `true` as "the request may succeed," never as "the card is showing right now."
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
