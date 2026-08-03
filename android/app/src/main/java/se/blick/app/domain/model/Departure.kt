package se.blick.app.domain.model

import java.time.Instant

data class LineRef(
    val id: Long,
    val designation: String,
    val transportMode: TransportMode,
)

data class StopAreaRef(val id: Long, val name: String, val type: String?)
data class StopPointRef(val id: Long, val name: String, val designation: String?)

/**
 * Domain-layer departure. Timestamps are parsed into [Instant] at the data-layer mapping
 * boundary (see data/remote/dto/DepartureDto.kt) — nothing above that boundary should
 * ever hold a raw timestamp string. `expectedTime` is nullable per the backend contract
 * (docs/api-contract.md §3: not guaranteed present); use [effectiveTime] rather than
 * reading either field directly.
 *
 * `minutesRemaining` is deliberately NOT a field here — the backend never returns it
 * (docs/api-contract.md §3). Compute it at render/update time from [effectiveTime] and
 * the current clock so the countdown never goes stale between network polls.
 */
data class Departure(
    val departureId: String,
    val line: LineRef,
    val direction: String?,
    val directionCode: Int?,
    val destination: String?,
    val via: String?,
    val stopArea: StopAreaRef,
    val stopPoint: StopPointRef,
    val scheduledTime: Instant,
    val expectedTime: Instant?,
    val state: String,
    val isCancelled: Boolean,
    val journey: Journey,
    val tripDeviations: List<TripDeviation>,
) {
    /** The time to actually display/count down to: `expectedTime ?? scheduledTime`. */
    val effectiveTime: Instant
        get() = expectedTime ?: scheduledTime
}

data class DeparturesResult(
    val fetchedAt: Instant,
    val siteId: Long,
    val departures: List<Departure>,
)
