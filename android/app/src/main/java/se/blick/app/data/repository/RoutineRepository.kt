package se.blick.app.data.repository

import kotlinx.coroutines.flow.Flow
import se.blick.app.domain.model.CommuteRoutine

/** Room-backed; see data/local/room. Supports any number of routines (see CommuteRoutine.kt). */
interface RoutineRepository {
    fun observeAll(): Flow<List<CommuteRoutine>>
    suspend fun getById(id: String): CommuteRoutine?
    suspend fun save(routine: CommuteRoutine)
    suspend fun delete(id: String)

    /** "Pause for today" control from the product doc's daily-operation flow. */
    suspend fun pauseForDate(id: String, date: java.time.LocalDate)
}
