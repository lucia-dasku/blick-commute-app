package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OneTimeEventDao {
    @Query("SELECT * FROM one_time_events ORDER BY eventDateEpochDay, eventTimeMinutes, name")
    fun observeAll(): Flow<List<OneTimeEventEntity>>

    @Query("SELECT * FROM one_time_events WHERE id = :id")
    suspend fun getById(id: String): OneTimeEventEntity?

    @Upsert
    suspend fun upsert(event: OneTimeEventEntity)

    @Query("DELETE FROM one_time_events WHERE id = :id")
    suspend fun deleteById(id: String)
}
