package se.blick.app.data.repository

import se.blick.app.data.local.room.StaleSnapshotDao
import se.blick.app.data.local.room.identity
import se.blick.app.data.local.room.toSnapshot
import se.blick.app.data.local.room.toStaleSnapshotEntity
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import javax.inject.Inject

class RoomStaleSnapshotRepository @Inject constructor(
    private val dao: StaleSnapshotDao,
) : StaleSnapshotRepository {

    override suspend fun get(routineId: String, identity: DepartureIdentity): LiveDeparturesSnapshot? {
        val entity = dao.getByRoutineId(routineId) ?: return null
        if (entity.identity() != identity) return null
        return entity.toSnapshot()
    }

    override suspend fun save(routineId: String, identity: DepartureIdentity, snapshot: LiveDeparturesSnapshot) {
        dao.upsert(toStaleSnapshotEntity(routineId, identity, snapshot))
    }

    override suspend fun clear(routineId: String) {
        dao.deleteByRoutineId(routineId)
    }
}
