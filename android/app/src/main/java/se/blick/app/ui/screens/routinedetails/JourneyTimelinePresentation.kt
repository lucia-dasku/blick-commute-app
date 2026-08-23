package se.blick.app.ui.screens.routinedetails

import se.blick.app.R
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.effectiveFirstDeparture
import java.time.Duration
import java.time.Instant

/** UI-only projection of an already-selected journey. It preserves the backend's leg order and
 * authoritative summary fields while giving Compose explicit transit, transfer, and walking rows
 * instead of asking the screen to infer a journey from formatted paragraphs. */
internal data class JourneyTimelinePresentation(
    val items: List<JourneyTimelineItem>,
    val finalArrivalTime: Instant,
    val transferCount: Int,
    val totalDurationMinutes: Long,
)

internal sealed interface JourneyTimelineItem {
    data class TransitLeg(
        val transportMode: TransportMode,
        val lineDesignation: String?,
        val originDisplayName: String,
        val destinationDisplayName: String,
        val direction: String?,
        val departureTime: Instant?,
        val disruptions: List<String>,
    ) : JourneyTimelineItem

    data class Transfer(
        val stationDisplayName: String,
        val durationMinutes: Long?,
    ) : JourneyTimelineItem

    data class Walk(
        val originDisplayName: String,
        val destinationDisplayName: String,
        val departureTime: Instant?,
        val durationMinutes: Long?,
        val disruptions: List<String>,
    ) : JourneyTimelineItem
}

internal fun JourneyPlan.toTimelinePresentation(): JourneyTimelinePresentation {
    val sourceLegs = legs.ifEmpty { listOf(firstLeg) }
    val items = buildList {
        var previousTransit: JourneyLeg? = null
        var meaningfulWalkSincePreviousTransit = false

        sourceLegs.forEach { leg ->
            if (leg.isStructuredWalk()) {
                if (leg.isMeaningfulWalk()) {
                    add(
                        JourneyTimelineItem.Walk(
                            originDisplayName = compactStationDisplayName(leg.originName),
                            destinationDisplayName = compactStationDisplayName(leg.destinationName),
                            departureTime = leg.departureTime,
                            durationMinutes = durationMinutes(leg.departureTime, leg.arrivalTime),
                            disruptions = leg.disruptions,
                        ),
                    )
                    meaningfulWalkSincePreviousTransit = true
                }
            } else {
                previousTransit?.let { previous ->
                    if (!meaningfulWalkSincePreviousTransit) {
                        add(
                            JourneyTimelineItem.Transfer(
                                stationDisplayName = compactStationDisplayName(
                                    previous.destinationName.ifBlank { leg.originName },
                                ),
                                durationMinutes = durationMinutes(previous.arrivalTime, leg.departureTime),
                            ),
                        )
                    }
                }
                add(leg.toTransitTimelineItem())
                previousTransit = leg
                meaningfulWalkSincePreviousTransit = false
            }
        }
    }

    return JourneyTimelinePresentation(
        items = items,
        finalArrivalTime = arrivalTime,
        transferCount = transferCount,
        totalDurationMinutes = Duration.between(effectiveFirstDeparture(), arrivalTime).toMinutes().coerceAtLeast(0),
    )
}

private fun JourneyLeg.toTransitTimelineItem() = JourneyTimelineItem.TransitLeg(
    transportMode = transportMode,
    lineDesignation = lineDesignation?.trim()?.takeIf(String::isNotEmpty),
    originDisplayName = compactStationDisplayName(originName),
    destinationDisplayName = compactStationDisplayName(destinationName),
    direction = direction?.trim()?.takeIf { it.isNotEmpty() && it != "-" },
    departureTime = departureTime,
    disruptions = disruptions,
)

/** WALK is represented as UNKNOWN in Android's current domain contract; the absent line is what
 * distinguishes it from an unfamiliar future transit mode that should still render as transit. */
private fun JourneyLeg.isStructuredWalk() = transportMode == TransportMode.UNKNOWN && lineDesignation.isNullOrBlank()

/** Preserve an informative connector, but omit an empty zero-information placeholder. */
private fun JourneyLeg.isMeaningfulWalk(): Boolean =
    originName.isNotBlank() || destinationName.isNotBlank() || durationMinutes(departureTime, arrivalTime) != null

/** Timestamps come directly from the structured leg data. Ceiling keeps a positive sub-minute
 * walk/transfer visible as one minute without inventing a duration when either timestamp is absent. */
private fun durationMinutes(start: Instant?, end: Instant?): Long? {
    if (start == null || end == null || end <= start) return null
    val seconds = Duration.between(start, end).seconds
    return (seconds + 59) / 60
}

/** Journey Planner currently supplies no separate locality/display-name fields on Android. The
 * only safely removable noise is therefore the exact redundant SL-area suffix requested by the
 * design. Every other comma suffix is preserved for disambiguation. */
internal fun compactStationDisplayName(name: String): String {
    val trimmed = name.trim()
    val suffix = ", Stockholm"
    if (!trimmed.endsWith(suffix, ignoreCase = true)) return trimmed
    return trimmed.dropLast(suffix.length).trim().ifEmpty { trimmed }
}

internal fun TransportMode.journeyLabelResId(): Int = when (this) {
    TransportMode.METRO -> R.string.journey_mode_metro
    TransportMode.TRAIN -> R.string.journey_mode_commuter_rail
    TransportMode.BUS -> R.string.journey_mode_bus
    TransportMode.TRAM -> R.string.journey_mode_tram
    TransportMode.SHIP, TransportMode.FERRY -> R.string.journey_mode_ferry
    TransportMode.TAXI, TransportMode.UNKNOWN -> R.string.transport_mode_unknown
}
