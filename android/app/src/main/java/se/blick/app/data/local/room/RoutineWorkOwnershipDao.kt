package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RoutineWorkOwnershipDao {
    @Query("SELECT ownerWorkId FROM routine_work_ownership WHERE routineId = :routineId")
    suspend fun getOwnerWorkId(routineId: String): String?

    // @Upsert (insert, or update in place on a primary-key conflict) rather than
    // @Insert(onConflict = OnConflictStrategy.REPLACE) -- see RoutineDao.upsert's own doc for
    // the general reason this codebase now prefers @Upsert: REPLACE resolves a conflict via a
    // real SQL DELETE followed by a fresh INSERT, which is simply unnecessary churn for a
    // single-column update like this one, even though (unlike RoutineEntity) nothing references
    // this table as a cascade-delete parent, so there is no correctness bug either way here.
    @Upsert
    suspend fun claim(entity: RoutineWorkOwnershipEntity)
}
