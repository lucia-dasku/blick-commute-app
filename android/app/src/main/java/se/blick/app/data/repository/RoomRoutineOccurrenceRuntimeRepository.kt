package se.blick.app.data.repository

import se.blick.app.data.local.room.RoutineOccurrenceRuntimeDao
import se.blick.app.data.local.room.RoutineOccurrenceRuntimeEntity
import javax.inject.Inject

class RoomRoutineOccurrenceRuntimeRepository @Inject constructor(
    private val dao: RoutineOccurrenceRuntimeDao,
) : RoutineOccurrenceRuntimeRepository {

    override suspend fun get(routineId: String): RoutineOccurrenceRuntimeState? =
        dao.get(routineId)?.let {
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = it.occurrenceWindowEndEpochMilli,
                monotonicStartElapsedRealtimeMillis = it.monotonicStartElapsedRealtimeMillis,
                bootCountAtStart = it.bootCountAtStart,
                hardStopEpochMilli = it.hardStopEpochMilli,
            )
        }

    override suspend fun save(routineId: String, state: RoutineOccurrenceRuntimeState) {
        dao.upsert(
            RoutineOccurrenceRuntimeEntity(
                routineId = routineId,
                occurrenceWindowEndEpochMilli = state.occurrenceWindowEndEpochMilli,
                monotonicStartElapsedRealtimeMillis = state.monotonicStartElapsedRealtimeMillis,
                bootCountAtStart = state.bootCountAtStart,
                hardStopEpochMilli = state.hardStopEpochMilli,
            ),
        )
    }

    override suspend fun clear(routineId: String) {
        dao.clear(routineId)
    }
}
