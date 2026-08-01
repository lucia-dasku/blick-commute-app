package se.blick.app.data.repository

import se.blick.app.domain.model.TransportMode

/**
 * Discovers the line/direction options available at a site during routine setup.
 *
 * The only implementation, [LiveDeparturesDirectionOptionsSource], is backed by SL
 * Transport's live departures feed, requested with a much longer forecast window
 * (`forecast=`[DIRECTION_DISCOVERY_FORECAST_MINUTES], 20 hours — see that constant's own
 * doc) than the live routine-details/notification polling paths use, specifically so a
 * route that isn't running RIGHT NOW (e.g. a weekday rush-hour-only bus, checked at
 * midday) still appears as a selectable option as long as it runs at all within the next
 * 20 hours. This substantially narrows, but does not eliminate, the underlying limitation:
 * SL Transport exposes no static, always-available schedule endpoint at all (only `lines`
 * — a flat, siteless line list — and `stop-points` — physical stop metadata, neither
 * carrying any line-to-site-with-direction association; verified directly against the
 * real API, not assumed), so a route running less often than once per 20-hour window
 * (e.g. a specific weekday-only service checked on the wrong day) can still be missed.
 * The interface exists so a future data source (e.g. a static schedule fallback, if SL
 * ever exposes one) could still replace [LiveDeparturesDirectionOptionsSource] without any
 * change to Room or the UI layer, which only ever depend on this interface.
 */
interface DirectionOptionsSource {
    suspend fun getDirectionOptions(siteId: Long): List<DirectionOption>
}

/**
 * One selectable (line, direction) pair. `destinationLabel` is display-only — per
 * docs/api-contract.md §10, a routine's real identity is
 * (siteId, lineId, transportMode, directionCode), never the destination text alone.
 */
data class DirectionOption(
    val lineId: Long,
    val lineDesignation: String,
    val transportMode: TransportMode,
    val directionCode: Int?,
    val destinationLabel: String?,
)
