package se.blick.app.domain.usecase

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode

/**
 * The fields that together identify "the same departure query" for a routine — two fetches
 * (or the same routine before/after an edit) only ever produce comparable departure data if
 * all four match. Shared by [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel]
 * (the foreground preview) and [se.blick.app.scheduling.RoutineActiveWindowWorker] (the
 * background notification loop) — both guard their own [LiveDeparturesSnapshot] fallback with
 * it, and both read/write the same [se.blick.app.data.repository.StaleSnapshotRepository]
 * keyed by it, so a cached snapshot can never be offered as a stale fallback to a fetch for a
 * genuinely different configuration: an edited routine's first failed fetch under its NEW
 * identity must never resurface its OLD identity's departures mislabelled as "stale" data for
 * the new one.
 */
data class DepartureIdentity(
    val siteId: Long,
    val lineId: Long?,
    val directionCode: Int?,
    val transportMode: TransportMode,
)

fun CommuteRoutine.departureIdentity(): DepartureIdentity =
    DepartureIdentity(siteId, lineId, directionCode, transportMode)
