package se.blick.app.notification

/**
 * The one shared source of truth for "can Blick actually deliver a visible notification right
 * now" — used by [se.blick.app.notification.AndroidRoutineNotifier] (before ever posting or
 * updating), [se.blick.app.scheduling.RoutineActiveWindowWorker] (before ever entering
 * foreground execution — see that class's own doc for why this check must happen BEFORE
 * `setForeground()`, not after it), and the routine details screen's own notification-status
 * hint (see `RoutineDetailsViewModel`'s `notificationAvailability` state). All three read
 * through [NotificationAvailabilityChecker] rather than each re-implementing their own version
 * of these checks, so they can never disagree about whether automatic delivery is actually
 * active.
 */
sealed interface NotificationAvailability {
    /** Nothing is blocking delivery: app-wide notifications are enabled, the runtime
     * `POST_NOTIFICATIONS` permission (API 33+) is granted (or simply not required below API
     * 33), and the Blick channel either doesn't exist yet or exists and has not been disabled.
     * A channel that doesn't exist yet is deliberately [Available], not a separate state — the
     * normal channel-creation path (see `RoutineNotificationBuilder.ensureChannel`) is allowed
     * to run the first time a notification is actually built. */
    data object Available : NotificationAvailability

    /** API 33+ only: the `POST_NOTIFICATIONS` runtime permission has not been granted. Checked
     * before the app-wide toggle below since it is the more specific, more actionable reason
     * on the API levels where it applies. */
    data object PermissionMissing : NotificationAvailability

    /** The app-wide notifications toggle is off in system Settings (the only relevant check on
     * API < 33; on API 33+ this is checked only once [PermissionMissing] has already been
     * ruled out). */
    data object AppDisabled : NotificationAvailability

    /** The Blick channel specifically exists AND has been set to `IMPORTANCE_NONE` by the user
     * — a separate, per-channel setting distinct from the app-wide toggle above. Never
     * recreated or modified just because it was found in this state — see
     * `RoutineNotificationBuilder.ensureChannel`'s own doc on why recreating a channel with the
     * same id is a no-op against the user's own choice, and why callers here should not even
     * attempt to build a notification (and thus never call `ensureChannel`) while this state is
     * current. */
    data object ChannelDisabled : NotificationAvailability
}

/** Single injectable seam for [NotificationAvailability] — see that type's own doc for why this
 * must be the one place every caller asks. */
interface NotificationAvailabilityChecker {
    fun check(): NotificationAvailability
}
