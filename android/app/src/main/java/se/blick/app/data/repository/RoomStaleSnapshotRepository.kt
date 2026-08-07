package se.blick.app.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import se.blick.app.data.local.room.StaleSnapshotDao
import se.blick.app.data.local.room.identity
import se.blick.app.data.local.room.toSnapshot
import se.blick.app.data.local.room.toStaleSnapshotEntity
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import javax.inject.Inject

private const val LOG_TAG = "RoomStaleSnapshotRepository"

class RoomStaleSnapshotRepository @Inject constructor(
    private val dao: StaleSnapshotDao,
) : StaleSnapshotRepository {

    override suspend fun get(routineId: String, identity: DepartureIdentity): LiveDeparturesSnapshot? {
        val entity = dao.getByRoutineId(routineId) ?: return null
        if (entity.identity() != identity) return null
        return try {
            entity.toSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            // departuresJson is corrupted, malformed, or no longer matches StaleDepartureRow's
            // shape (e.g. an old row surviving an app update that changed it) -- this is only
            // ever fallback data, so a broken row must behave exactly like no cache existed,
            // never escape and interfere with the active-window worker or Routine Details'
            // own refresh. Deletes the bad row so future reads don't keep re-decoding (and
            // re-logging) the same corruption every tick; a failure to delete it is itself
            // non-fatal -- see the nested catch below -- this read must still report a cache
            // miss either way.
            Log.w(LOG_TAG, "Stale snapshot for routine $routineId is corrupted; deleting it and treating this as a cache miss", e)
            try {
                dao.deleteByRoutineId(routineId)
            } catch (deleteError: CancellationException) {
                throw deleteError
            } catch (deleteError: Exception) {
                Log.w(
                    LOG_TAG,
                    "Failed to delete corrupted stale snapshot for routine $routineId; it will be treated as a " +
                        "cache miss now regardless, and cleanup will simply be retried on the next read",
                    deleteError,
                )
            }
            null
        }
    }

    override suspend fun save(routineId: String, identity: DepartureIdentity, snapshot: LiveDeparturesSnapshot) {
        dao.upsert(toStaleSnapshotEntity(routineId, identity, snapshot))
    }

    override suspend fun clear(routineId: String) {
        dao.deleteByRoutineId(routineId)
    }
}
