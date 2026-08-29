package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable, process-safe lease for Blick's one global active commute presentation.
 * A singleton row is intentional: notification and widget content are global even when the
 * saved source is a recurring routine or a one-time event.
 */
@Entity(tableName = "active_commute_ownership")
data class ActiveCommuteOwnershipEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val sourceType: String,
    val sourceId: String,
    val ownerRunId: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
