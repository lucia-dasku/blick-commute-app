package se.blick.app.data.local.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ActiveCommuteOwnershipDao {
    @Query("SELECT * FROM active_commute_ownership WHERE singletonId = 1")
    suspend fun get(): ActiveCommuteOwnershipEntity?

    @Upsert
    suspend fun claim(entity: ActiveCommuteOwnershipEntity)

    @Query(
        """DELETE FROM active_commute_ownership
            WHERE singletonId = 1
            AND sourceType = :sourceType
            AND sourceId = :sourceId
            AND ownerRunId = :ownerRunId""",
    )
    suspend fun releaseIfOwner(sourceType: String, sourceId: String, ownerRunId: String): Int
}
