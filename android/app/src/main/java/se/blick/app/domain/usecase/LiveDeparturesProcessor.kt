package se.blick.app.domain.usecase

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import java.time.Instant

internal const val DEFAULT_MAX_DEPARTURES = 2

/**
 * Pure, deterministic filtering/sorting/countdown logic for turning a raw
 * [DeparturesResult] into the [PreparedDeparture]s relevant to one saved [CommuteRoutine]
 * at one instant.
 *
 * Contains no I/O, no coroutines, and no reference to the system clock — `now` is always
 * supplied by the caller (see [GetLiveDeparturesUseCase]), so this object is testable with
 * fixed [Instant] values only and never depends on wall-clock time.
 */
object LiveDeparturesProcessor {
    /**
     * Filters [result]'s departures to those matching [routine] (transport mode always;
     * line and direction only when the routine has pinned a specific value), drops any
     * whose [Departure.effectiveTime] is before [now], sorts the remainder by effective
     * time ascending, and returns at most [maxDepartures] — each converted to a
     * [PreparedDeparture] with a countdown computed relative to [now]. Existing callers default
     * to [DEFAULT_MAX_DEPARTURES]; the foreground Routine Details screen may explicitly retain
     * a few more rows from the same fetch without enlarging worker results globally.
     *
     * Cancelled departures are deliberately never filtered out here: a future cancelled
     * departure is still relevant information for the caller to show, just flagged via
     * [PreparedDeparture.isCancelled].
     */
    fun prepare(
        result: DeparturesResult,
        routine: CommuteRoutine,
        now: Instant,
        maxDepartures: Int = DEFAULT_MAX_DEPARTURES,
    ): List<PreparedDeparture> {
        require(maxDepartures > 0) { "maxDepartures must be positive" }
        return result.departures
            .asSequence()
            .filter { it.line.transportMode == routine.transportMode }
            .filter { routine.lineId == null || it.line.id == routine.lineId }
            .filter { routine.directionCode == null || it.directionCode == routine.directionCode }
            .filter { !it.effectiveTime.isBefore(now) }
            .sortedBy { it.effectiveTime }
            .take(maxDepartures)
            .map { it.toPrepared(now) }
            .toList()
    }

    private fun Departure.toPrepared(now: Instant): PreparedDeparture {
        // effectiveTime >= now is already guaranteed by the caller's filter above; see
        // countdownMinutes's own doc for why it stays defensive regardless.
        val minutesRemaining = countdownMinutes(now, effectiveTime)
        return PreparedDeparture(
            departureId = departureId,
            lineDesignation = line.designation,
            direction = direction,
            destination = destination,
            scheduledTime = scheduledTime,
            expectedTime = expectedTime,
            effectiveTime = effectiveTime,
            minutesRemaining = minutesRemaining,
            isRealTime = expectedTime != null,
            isCancelled = isCancelled,
            state = state,
            journeyState = journey.state,
            predictionState = journey.predictionState,
            tripDeviations = tripDeviations,
        )
    }
}
