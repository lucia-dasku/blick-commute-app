package se.blick.app.data.repository

/**
 * Runtime state for [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own
 * `HARD_FOREGROUND_RUNTIME_CAP_MINUTES` safety cap — see
 * `data/local/room/RoutineOccurrenceRuntimeEntity.kt`'s own doc for exactly what each field means
 * and why this must be durable (Room-backed) rather than an in-memory field, which would reset on
 * every fresh worker instance — exactly the case this exists to survive.
 */
interface RoutineOccurrenceRuntimeRepository {
    /** Null if nothing is currently recorded for [routineId] (a fresh routine, or one whose
     * previous occurrence already finished and was [clear]ed). */
    suspend fun get(routineId: String): RoutineOccurrenceRuntimeState?

    /** Overwrites whatever was previously recorded for [routineId] — callers only do this once,
     * the first time a given occurrence is confirmed to have started (see
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own use of this). */
    suspend fun save(routineId: String, state: RoutineOccurrenceRuntimeState)

    /** Removes any recorded state for [routineId] — a harmless no-op if none exists. Called once
     * an occurrence is genuinely over (its window ended naturally, or the hard cap stopped it),
     * so a later, genuinely NEW occurrence never has to share space with stale data (though its
     * different [RoutineOccurrenceRuntimeState.occurrenceWindowEndEpochMilli] would already make
     * old state irrelevant on its own). */
    suspend fun clear(routineId: String)
}

data class RoutineOccurrenceRuntimeState(
    val occurrenceWindowEndEpochMilli: Long,
    val monotonicStartElapsedRealtimeMillis: Long,
    val bootCountAtStart: Int,
    val hardStopEpochMilli: Long,
)
