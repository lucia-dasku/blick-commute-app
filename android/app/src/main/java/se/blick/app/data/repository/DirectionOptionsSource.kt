package se.blick.app.data.repository

import se.blick.app.domain.model.TransportMode

/**
 * Discovers the line/direction options available at a site during routine setup.
 *
 * KNOWN LIMITATION (see docs/api-contract.md §10): the only implementation available in
 * this scaffold, [LiveDeparturesDirectionOptionsSource], is backed by SL Transport's live
 * departures feed, which only reflects lines/directions actually running within its
 * current forecast window. A route that isn't running right now (e.g. a weekday
 * rush-hour-only bus, checked on a Sunday) will not appear as a selectable option. This
 * is a real, undismissed product gap, not solved by this interface — the interface
 * exists specifically so a future data source (e.g. a static schedule fallback) can
 * replace [LiveDeparturesDirectionOptionsSource] without any change to Room or the UI
 * layer, which only ever depend on this interface.
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
