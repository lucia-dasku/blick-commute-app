package se.blick.app.data.repository

import se.blick.app.data.local.room.ActiveCommuteOwnershipDao
import se.blick.app.data.local.room.ActiveCommuteOwnershipEntity
import se.blick.app.domain.model.ActiveCommuteSource
import se.blick.app.domain.model.ActiveCommuteSourceType
import se.blick.app.domain.model.activeCommuteSource
import se.blick.app.domain.model.type
import javax.inject.Inject

class RoomActiveCommuteOwnershipRepository @Inject constructor(
    private val dao: ActiveCommuteOwnershipDao,
) : ActiveCommuteOwnershipRepository {
    override suspend fun claim(source: ActiveCommuteSource, ownerRunId: String) {
        dao.claim(
            ActiveCommuteOwnershipEntity(
                sourceType = source.type.name,
                sourceId = source.id,
                ownerRunId = ownerRunId,
            ),
        )
    }

    override suspend fun currentOwner(): ActiveCommuteOwnership? {
        val row = dao.get() ?: return null
        val type = runCatching { ActiveCommuteSourceType.valueOf(row.sourceType) }.getOrNull() ?: return null
        return ActiveCommuteOwnership(activeCommuteSource(type, row.sourceId), row.ownerRunId)
    }

    override suspend fun releaseIfOwner(source: ActiveCommuteSource, ownerRunId: String): Boolean =
        dao.releaseIfOwner(source.type.name, source.id, ownerRunId) == 1
}
