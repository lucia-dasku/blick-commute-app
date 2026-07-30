package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StaleSnapshotDao {
    @Query("SELECT * FROM stale_snapshots WHERE routineId = :routineId")
    suspend fun getByRoutineId(routineId: String): StaleSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StaleSnapshotEntity)

    @Query("DELETE FROM stale_snapshots WHERE routineId = :routineId")
    suspend fun deleteByRoutineId(routineId: String)
}
