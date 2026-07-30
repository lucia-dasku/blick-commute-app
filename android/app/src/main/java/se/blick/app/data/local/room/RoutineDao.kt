package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY name ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity)

    @Update
    suspend fun update(routine: RoutineEntity)

    @Delete
    suspend fun delete(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM routines LIMIT 1)")
    suspend fun hasAny(): Boolean
}
