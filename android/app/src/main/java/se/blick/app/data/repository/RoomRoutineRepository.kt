package se.blick.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.blick.app.data.local.room.RoutineDao
import se.blick.app.data.local.room.toDomain
import se.blick.app.data.local.room.toEntity
import se.blick.app.domain.model.CommuteRoutine
import java.time.LocalDate
import javax.inject.Inject

class RoomRoutineRepository @Inject constructor(
    private val dao: RoutineDao,
) : RoutineRepository {

    override fun observeAll(): Flow<List<CommuteRoutine>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): CommuteRoutine? = dao.getById(id)?.toDomain()

    override suspend fun save(routine: CommuteRoutine) = dao.upsert(routine.toEntity())

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun pauseForDate(id: String, date: LocalDate) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(pausedDateEpochDay = date.toEpochDay()))
    }

    override suspend fun clearPause(id: String) {
        val existing = dao.getById(id) ?: return
        if (existing.pausedDateEpochDay == null) return
        dao.update(existing.copy(pausedDateEpochDay = null))
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        val existing = dao.getById(id) ?: return
        if (existing.enabled == enabled) return
        dao.update(existing.copy(enabled = enabled))
    }

    override suspend fun hasAnyRoutine(): Boolean = dao.hasAny()
}
