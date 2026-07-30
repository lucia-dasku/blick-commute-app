package se.blick.app.domain.usecase

import java.time.Duration
import java.time.Instant

private const val SECONDS_PER_MINUTE = 60L

/**
 * Ceiling-minute countdown, shared by [LiveDeparturesProcessor] (which computes
 * [PreparedDeparture.minutesRemaining] once, at fetch time) and
 * [se.blick.app.notification.RoutineNotificationMapper] (which must recompute the same
 * countdown again, later, at notification-render time — a cached `minutesRemaining` goes
 * stale the moment real time moves on, so the notifier can never trust it). Kept as a single
 * top-level function rather than duplicated in both places, so the rounding rule only ever
 * has one definition to change.
 *
 * Ceiling-divides seconds into minutes using plain integer arithmetic (no floating point):
 * 0s -> 0 min, 30s -> 1 min, 60s -> 1 min, 61s -> 2 min. This matches the product requirement
 * that a departure "30 seconds away" reads as 1 minute, while one exactly at `now` reads as 0.
 */
fun countdownMinutes(now: Instant, effectiveTime: Instant): Long {
    // coerceAtLeast(0) keeps this function safe/non-negative even if a caller passes an
    // effectiveTime that is already in the past (callers are expected to filter those out
    // themselves — see LiveDeparturesProcessor.prepare and RoutineNotificationMapper — but
    // this function stays defensive on its own regardless).
    val secondsUntil = Duration.between(now, effectiveTime).seconds.coerceAtLeast(0)
    return (secondsUntil + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
}
