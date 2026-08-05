package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RoutineOccurrenceRuntimeDao {
    @Query("SELECT * FROM routine_occurrence_runtime WHERE routineId = :routineId")
    suspend fun get(routineId: String): RoutineOccurrenceRuntimeEntity?

    // @Upsert, matching RoutineWorkOwnershipDao.claim's own doc on why this codebase prefers it
    // over @Insert(onConflict = REPLACE) for a single-row-per-key update like this one.
    @Upsert
    suspend fun upsert(entity: RoutineOccurrenceRuntimeEntity)

    @Query("DELETE FROM routine_occurrence_runtime WHERE routineId = :routineId")
    suspend fun clear(routineId: String)
}
