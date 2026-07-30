package se.blick.app.notification

/**
 * The real, observable outcome of a single [RoutineNotifier.showOrUpdate] call — returned so
 * callers (today, only the debug trigger; later, any real refresh path) can distinguish an
 * actual successful post from "nothing happened." Before this existed, `showOrUpdate` returned
 * `Unit` and silently did nothing on a disabled-notifications guard or a caught exception,
 * which let the debug UI claim "Test notification posted" even when no notification was ever
 * posted.
 *
 * Deliberately small and platform-neutral — no [Exception] or Android-specific type is
 * exposed here, matching the rest of the app's convention of never surfacing raw technical
 * detail to a caller/UI (see `RoutineDetailsViewModel`/`RoutineCreateViewModel`'s error
 * strings).
 */
sealed interface NotificationPostResult {
    /** The notification was actually built and handed to the system successfully. */
    data object Posted : NotificationPostResult

    /** Nothing was posted because notifications are disabled for the app (in Settings),
     * on API 33+ the `POST_NOTIFICATIONS` runtime permission has not been granted, or the
     * Blick notification channel specifically has been disabled by the user
     * (`NotificationManager.IMPORTANCE_NONE`) — a separate, per-channel setting distinct
     * from the app-wide toggle. */
    data object NotificationsDisabled : NotificationPostResult

    /** Nothing was posted because building or posting the notification threw unexpectedly.
     * No exception detail is carried here — see this interface's own doc comment. */
    data object Failed : NotificationPostResult
}
