package se.blick.app.data.repository

import se.blick.app.data.local.room.RoutineWorkOwnershipDao
import se.blick.app.data.local.room.RoutineWorkOwnershipEntity
import javax.inject.Inject

class RoomRoutineWorkOwnershipRepository @Inject constructor(
    private val dao: RoutineWorkOwnershipDao,
) : RoutineWorkOwnershipRepository {

    override suspend fun claim(routineId: String, workId: String) {
        dao.claim(RoutineWorkOwnershipEntity(routineId, workId))
    }

    override suspend fun isOwner(routineId: String, workId: String): Boolean =
        dao.getOwnerWorkId(routineId) == workId
}
