package se.blick.app.data.repository

/**
 * Durable per-run content-ownership tracking for [se.blick.app.scheduling.RoutineActiveWindowWorker]
 * — see `data/local/room/RoutineWorkOwnershipEntity.kt`'s own doc for the full race this exists
 * to close, and that worker's own doc for exactly where [claim]/[isOwner] are called.
 */
interface RoutineWorkOwnershipRepository {
    /** Records [workId] (the calling worker's own WorkManager-assigned id) as the current
     * owner of [routineId]'s active-window content, overwriting whatever was recorded before —
     * this IS what invalidates a previous owner. Called once, as early as possible in a run,
     * right after it actually starts producing content (entering foreground execution). */
    suspend fun claim(routineId: String, workId: String)

    /** Whether [workId] is STILL the recorded owner of [routineId]'s content at the moment this
     * is called — used immediately before removing/clearing notification or widget content in
     * a `finally` block, so a run that has since been superseded by a newer one can detect that
     * and skip cleanup instead of clobbering the newer run's own content. */
    suspend fun isOwner(routineId: String, workId: String): Boolean
}
