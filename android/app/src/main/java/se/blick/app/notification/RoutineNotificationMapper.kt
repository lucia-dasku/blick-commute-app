package se.blick.app.notification

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.usecase.countdownMinutes
import java.time.Instant

/**
 * Pure, deterministic conversion from a [CommuteRoutine] + [LiveDeparturesState] + one
 * supplied [Instant] to a [RoutineNotificationModel]. Contains no I/O, no coroutines, no
 * Android [android.content.Context]/notification API, and no string-resource lookup — `now`
 * is always supplied by the caller (matching [se.blick.app.domain.usecase.LiveDeparturesProcessor]'s
 * own convention), so this object is testable with plain JVM unit tests and a fixed clock.
 *
 * Never re-filters departures by routine identity (site/line/direction/mode) — that already
 * happened upstream in [se.blick.app.domain.usecase.LiveDeparturesProcessor] before a
 * [LiveDeparturesState.Live]/[LiveDeparturesState.Stale] snapshot was ever produced. This
 * mapper's only job is: recompute each departure's countdown against its own supplied `now`
 * (never trust [PreparedDeparture.minutesRemaining], which was only valid at the instant the
 * underlying fetch happened), drop any departure whose effective time has since passed, and
 * cap at two — the same two-departure maximum the live-departures engine already enforces,
 * re-applied here defensively since a [LiveDeparturesState.Stale] snapshot can be arbitrarily
 * old by the time this mapper runs.
 */
object RoutineNotificationMapper {

    private const val MAX_DEPARTURES = 2

    /**
     * [topDisruption] is the single highest-priority currently-relevant disruption for this
     * routine, if any was fetched successfully (see [se.blick.app.domain.usecase.GetDisruptionsUseCase]
     * and [se.blick.app.domain.model.relevantDisruptions] — already priority-ordered before
     * this mapper ever sees it, so "top" is simply the first element the caller passes in).
     * Defaults to null so every existing call site (the worker's own `Loading` placeholder
     * post, the debug notification trigger) keeps compiling without having fetched
     * disruptions itself.
     */
    fun map(
        routine: CommuteRoutine,
        departuresState: LiveDeparturesState,
        now: Instant,
        topDisruption: Disruption? = null,
    ): RoutineNotificationModel =
        RoutineNotificationModel(
            routineId = routine.id,
            stationName = routine.siteName,
            lineLabel = routine.lineDesignation,
            directionLabel = routine.destinationLabel,
            content = departuresState.toContent(now),
            disruptionHeadline = topDisruption?.message?.header,
            disruptionDetails = topDisruption?.message?.details,
        )

    private fun LiveDeparturesState.toContent(now: Instant): RoutineNotificationContent = when (this) {
        is LiveDeparturesState.Loading -> RoutineNotificationContent.Loading
        is LiveDeparturesState.Live -> {
            // A Live snapshot was non-empty at fetch time (see LiveDeparturesState.Live's own
            // doc), but by the time this mapper runs (e.g. a debug-trigger tap some time
            // later), every one of its departures may have since passed toRows's own
            // effectiveTime filter. Live(emptyList()) would render as a live, updating
            // notification with no departure rows at all -- that's not a live result, it's
            // "nothing upcoming right now", so it must be reported as NoUpcomingDepartures
            // instead, never as an empty Live. A Stale snapshot is NOT re-derived this way
            // (see the Stale branch below): staleness must keep communicating "the last
            // refresh failed", even when its own departures have all since expired too.
            val rows = snapshot.toRows(now)
            if (rows.isEmpty()) {
                RoutineNotificationContent.NoUpcomingDepartures(lastCheckedAt = snapshot.fetchedAt)
            } else {
                RoutineNotificationContent.Live(rows)
            }
        }
        is LiveDeparturesState.Stale -> RoutineNotificationContent.Stale(
            departures = snapshot.toRows(now),
            lastCheckedAt = snapshot.fetchedAt,
        )
        is LiveDeparturesState.NoUpcomingDepartures -> RoutineNotificationContent.NoUpcomingDepartures(lastCheckedAt = fetchedAt)
        is LiveDeparturesState.Offline -> RoutineNotificationContent.Offline
        is LiveDeparturesState.Unavailable -> RoutineNotificationContent.Unavailable
    }

    /**
     * Recomputes each departure's countdown against [now], drops any that have since
     * passed (a [LiveDeparturesState.Stale] snapshot in particular may contain departures
     * that were upcoming when fetched but have since departed), and caps at
     * [MAX_DEPARTURES] — sorted by effective time first, so a stale/out-of-order snapshot
     * always surfaces its two soonest still-upcoming departures.
     */
    private fun LiveDeparturesSnapshot.toRows(now: Instant): List<NotificationDepartureRow> =
        departures
            .asSequence()
            .filter { !it.effectiveTime.isBefore(now) }
            .sortedBy { it.effectiveTime }
            .take(MAX_DEPARTURES)
            .map { it.toRow(now) }
            .toList()

    private fun PreparedDeparture.toRow(now: Instant): NotificationDepartureRow = NotificationDepartureRow(
        lineDesignation = lineDesignation,
        // Null when neither the destination nor a direction description is known — matches
        // RoutineDetailsScreen's own `destination ?: stringResource(R.string.direction_unknown_destination)`
        // fallback convention; RoutineNotificationBuilder applies that same fallback string.
        destinationLabel = destination ?: direction,
        effectiveTime = effectiveTime,
        minutesRemaining = countdownMinutes(now, effectiveTime),
        isRealTime = isRealTime,
        isCancelled = isCancelled,
    )
}
