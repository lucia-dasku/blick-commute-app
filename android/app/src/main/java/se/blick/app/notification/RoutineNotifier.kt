package se.blick.app.notification

/**
 * Represents the "one ongoing, updating notification" requirement from the product doc
 * (never a new notification per refresh — see the app's current one-routine beta limit,
 * which is why neither method here needs a routine id: there is at most one notification,
 * for at most one routine, ever). The concrete implementation ([AndroidRoutineNotifier])
 * owns the stable notification id and channel entirely; this interface never receives or
 * exposes either, so callers cannot accidentally post a second, distinct notification.
 *
 * Deliberately never receives a raw
 * [se.blick.app.domain.model.DeparturesResult] or
 * [se.blick.app.domain.usecase.LiveDeparturesState] — those must already have been converted
 * to a [RoutineNotificationModel] via [RoutineNotificationMapper] by the caller, so this
 * layer never re-implements the existing departure-filtering/countdown logic that already
 * lives in [se.blick.app.domain.usecase.LiveDeparturesProcessor].
 *
 * `minSdk = 26` means notification channels are always available here — no
 * `Build.VERSION.SDK_INT` branch is needed for channel creation.
 *
 * Scheduling (deciding *when* this gets called automatically) is implemented — see
 * `scheduling/WorkManagerRoutineScheduler` and `scheduling/RoutineActiveWindowWorker`, which
 * activates each routine's window and calls [showOrUpdate] roughly every 30 seconds while it
 * is active, only after confirming notification delivery is actually available (see
 * `NotificationAvailabilityChecker`) so a permission-missing/app-disabled/channel-disabled
 * device never enters that loop. The debug-only manual trigger on the routine details screen
 * (see RoutineDetailsViewModel) remains available alongside it for manual testing.
 */
interface RoutineNotifier {
    /** Posts the notification if none is showing yet, or updates the existing one in place
     * (same stable id) otherwise. Must fail safely (never throw) if app notifications are
     * disabled, the POST_NOTIFICATIONS permission is unavailable, or the Blick notification
     * channel specifically has been disabled by the user (`IMPORTANCE_NONE`) — instead,
     * returns the real outcome as a [NotificationPostResult] so a caller (e.g. the debug
     * trigger) can never mistake "nothing was posted" for success. Never recreates or
     * modifies an existing channel to override the user's own settings. */
    fun showOrUpdate(model: RoutineNotificationModel): NotificationPostResult

    /** Cancels the notification if one is currently showing; a no-op otherwise. Idempotent
     * and non-throwing — unlike [showOrUpdate], there is no meaningful failure a caller needs
     * to react to, so this does not return a result type. */
    fun remove()
}
