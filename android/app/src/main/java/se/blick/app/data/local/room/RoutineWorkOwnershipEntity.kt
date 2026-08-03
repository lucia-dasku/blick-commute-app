package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Records which [androidx.work.WorkRequest] (by its own WorkManager-assigned
 * [androidx.work.WorkInfo.getId], stored as its string form) currently "owns" one routine's
 * active-window notification/widget content — see
 * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own doc on why this exists: an old
 * worker replaced by a new one (e.g. editing an active routine reschedules it, replacing the
 * running work with a fresh request) must never let its own `finally`-block cleanup clear
 * content the REPLACEMENT worker has already started posting. Every run claims ownership for
 * itself as soon as it starts (overwriting whatever was recorded before — that IS what
 * "replacing or superseding work invalidates the previous owner" means), and only removes
 * notification/widget content in its own `finally` block if it is STILL the recorded owner at
 * that point.
 *
 * One row per routine ([routineId] is both the primary key and, via the declared [ForeignKey],
 * a reference to `routines.id`) — `ON DELETE CASCADE` means deleting a routine automatically
 * removes its ownership record too, with no separate cleanup call needed anywhere a routine is
 * deleted. Room-backed (not in-memory) specifically so ownership stays correct across process
 * recreation: a worker run in a freshly-recreated process must be able to tell whether an
 * EARLIER process's worker run (now dead, but whose own `finally` might still be unwinding
 * within a brief teardown window) still "owns" the content it's looking at.
 */
@Entity(
    tableName = "routine_work_ownership",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoutineWorkOwnershipEntity(
    @PrimaryKey val routineId: String,
    val ownerWorkId: String,
)
