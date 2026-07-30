package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room-persisted durable stale-fallback cache: the last successful [se.blick.app.domain.usecase.LiveDeparturesSnapshot]
 * for one routine, scoped to the exact [se.blick.app.domain.usecase.DepartureIdentity] that
 * produced it — see that class's own doc on why the identity must be stored and re-checked,
 * not just the departures themselves.
 *
 * One row per routine ([routineId] is both the primary key and, via the declared
 * [ForeignKey], a reference to `routines.id`) — `ON DELETE CASCADE` means deleting a routine
 * automatically removes its stale snapshot too, with no separate cleanup call needed anywhere
 * a routine is deleted.
 *
 * [departuresJson] stores the snapshot's departures as a JSON-encoded list (see
 * StaleSnapshotMappers.kt's `StaleDepartureRow`) rather than a second relational child table —
 * this is always read/written as one atomic unit (never queried per-departure), so a single
 * column keeps this simple rather than introducing `@Relation`/`@Transaction` machinery for no
 * real benefit.
 */
@Entity(
    tableName = "stale_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StaleSnapshotEntity(
    @PrimaryKey val routineId: String,
    val siteId: Long,
    val lineId: Long?,
    val directionCode: Int?,
    val transportMode: String,
    val fetchedAtEpochMilli: Long,
    val departuresJson: String,
)
