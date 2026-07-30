package se.blick.app.data.repository

import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot

/**
 * Durable last-successful-fetch cache, keyed by routine id and scoped to one specific
 * [DepartureIdentity] — survives process death (Room-backed; see
 * `data/local/room/StaleSnapshotEntity.kt`), unlike a plain in-memory field. Shared by both
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel] (the foreground preview)
 * and [se.blick.app.scheduling.RoutineActiveWindowWorker] (the background notification loop),
 * so either one's successful fetch can serve as the other's stale fallback, and neither loses
 * it if the process is killed and restarted.
 */
interface StaleSnapshotRepository {
    /** Returns the persisted snapshot for [routineId] only if it was captured for the exact
     * same [identity] — see [DepartureIdentity]'s own doc on why a snapshot from a routine's
     * old configuration (before an edit changed its site/line/direction/mode) must never be
     * offered as a stale fallback for its new one. */
    suspend fun get(routineId: String, identity: DepartureIdentity): LiveDeparturesSnapshot?

    suspend fun save(routineId: String, identity: DepartureIdentity, snapshot: LiveDeparturesSnapshot)

    /** Called when a routine's departure-relevant identity changes (an edit) — an old snapshot
     * must never linger to be wrongly matched later. Not needed on routine deletion: the
     * underlying row's `ON DELETE CASCADE` foreign key already removes it automatically. */
    suspend fun clear(routineId: String)
}
