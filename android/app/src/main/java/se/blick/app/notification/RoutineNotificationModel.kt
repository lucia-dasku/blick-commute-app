package se.blick.app.notification

import java.time.Instant

/**
 * Pure presentation model for "the one ongoing commute notification" (see [RoutineNotifier]).
 * Produced only by [RoutineNotificationMapper], from a
 * [se.blick.app.domain.model.CommuteRoutine] + [se.blick.app.domain.usecase.LiveDeparturesState]
 * + a supplied [Instant] — never constructed by anything that also touches Android
 * notification APIs, so that mapping stays testable as plain JVM unit tests with a fixed
 * clock. This model must contain everything [RoutineNotificationBuilder] needs to render the
 * notification; no [android.content.Context], notification API, or string-resource lookup is
 * reachable from here.
 */
data class RoutineNotificationModel(
    /** Identifies which routine to reopen when the notification is tapped — see
     * [RoutineNotificationBuilder]'s content [android.app.PendingIntent]. */
    val routineId: String,
    val stationName: String,
    /** The routine's pinned line designation (e.g. "14"), or null if the routine never
     * pinned a specific line — [RoutineNotificationBuilder] supplies a fallback string
     * resource in that case rather than this model hard-coding English wording. */
    val lineLabel: String?,
    /** The routine's pinned destination/direction label, or null if the routine never
     * pinned one — same fallback rule as [lineLabel]. */
    val directionLabel: String?,
    val content: RoutineNotificationContent,
)

/**
 * Mutually exclusive notification states — mirrors
 * [se.blick.app.domain.usecase.LiveDeparturesState] one-for-one (see
 * [RoutineNotificationMapper]), modelled as a sealed hierarchy rather than a flat "mode" enum
 * plus optional fields so that, for example, a [Stale] notification can never be constructed
 * without its required `lastCheckedAt`, and an [Offline] one can never carry a stale departures
 * list it has no business showing.
 */
sealed interface RoutineNotificationContent {

    /** A successful, current fetch with at least one upcoming departure. */
    data class Live(val departures: List<NotificationDepartureRow>) : RoutineNotificationContent

    /** The latest refresh failed, but [departures] is the last successful fetch — shown
     * instead of an error, alongside [lastCheckedAt] so the person can judge how current it
     * still is. [departures] may be empty if every previously-fetched departure has since
     * passed (see [RoutineNotificationMapper]'s past-departure filtering). */
    data class Stale(val departures: List<NotificationDepartureRow>, val lastCheckedAt: Instant) : RoutineNotificationContent

    /** A successful, current fetch with zero upcoming departures — not a failure. */
    data class NoUpcomingDepartures(val lastCheckedAt: Instant) : RoutineNotificationContent

    /** The fetch failed with a connectivity-shaped error and there is no previous
     * successful snapshot to fall back on. */
    data object Offline : RoutineNotificationContent

    /** The fetch failed (any other reason) and there is no previous successful snapshot to
     * fall back on. */
    data object Unavailable : RoutineNotificationContent

    /** Only meaningful for the very first notification posted before any fetch has
     * resolved (e.g. the debug trigger's initial post) — a real refresh-in-place must never
     * regress an already-live notification back to this quiet "updating" state. */
    data object Loading : RoutineNotificationContent
}

/**
 * One notification-ready departure line — capped at two per [RoutineNotificationModel] by
 * [RoutineNotificationMapper] (matching the existing live-departures engine's own two-departure
 * maximum; this class does not re-enforce that cap itself).
 *
 * [minutesRemaining] is always recomputed by the mapper from [effectiveTime] and its own
 * supplied `now` — never copied from a possibly-stale
 * [se.blick.app.domain.usecase.PreparedDeparture.minutesRemaining].
 */
data class NotificationDepartureRow(
    val lineDesignation: String,
    /** Null when the departure has neither a destination nor a direction description —
     * [RoutineNotificationBuilder] applies the same fallback string resource
     * [se.blick.app.R.string.direction_unknown_destination] used elsewhere in the app. */
    val destinationLabel: String?,
    val effectiveTime: Instant,
    val minutesRemaining: Long,
    val isRealTime: Boolean,
    val isCancelled: Boolean,
)
