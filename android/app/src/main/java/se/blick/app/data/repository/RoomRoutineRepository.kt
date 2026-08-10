package se.blick.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.blick.app.data.local.room.RoutineDao
import se.blick.app.data.local.room.toDomain
import se.blick.app.data.local.room.toEntity
import se.blick.app.domain.model.CommuteRoutine
import java.time.LocalDate
import javax.inject.Inject
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.RoutineTierPolicy
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.usecase.RoutineOverlapValidationResult
import se.blick.app.domain.usecase.RoutineScheduleOverlapValidator

class RoutineTierLimitException : IllegalStateException("This routine requires Blick Premium")
class RoutineScheduleOverlapException(val conflictingRoutineId: String) :
    IllegalStateException("Routine overlaps another active schedule")

class RoomRoutineRepository @Inject constructor(
    private val dao: RoutineDao,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore,
) : RoutineRepository {

    override fun observeAll(): Flow<List<CommuteRoutine>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): CommuteRoutine? = dao.getById(id)?.toDomain()

    override suspend fun save(routine: CommuteRoutine) {
        val existing = dao.getAll().map { it.toDomain() }
        if (!entitlementRepository.entitlement.value.hasPremiumAccess) {
            val selected = RoutineTierPolicy.eligibleFreeRoutine(existing, freeRoutineSelectionStore.selectedRoutineId.value)
            val isExistingEligible = existing.any { it.id == routine.id } && selected?.id == routine.id
            // After a refund/revocation, Premium-only exact routines remain stored and locked.
            // They must not prevent the user from creating the one line routine Free can run.
            val isFirstLineRoutine = existing.none { it.type == RoutineType.LINE_DIRECTION } &&
                routine.type == RoutineType.LINE_DIRECTION
            if (!isExistingEligible && !isFirstLineRoutine) throw RoutineTierLimitException()
            if (routine.type != RoutineType.LINE_DIRECTION) throw RoutineTierLimitException()
        }
        when (val overlap = RoutineScheduleOverlapValidator.validate(routine, existing)) {
            RoutineOverlapValidationResult.Valid -> Unit
            is RoutineOverlapValidationResult.Overlaps -> throw RoutineScheduleOverlapException(overlap.routineId)
        }
        dao.upsert(routine.toEntity())
        if (existing.none { it.type == RoutineType.LINE_DIRECTION } && routine.type == RoutineType.LINE_DIRECTION) {
            freeRoutineSelectionStore.select(routine.id)
        }
    }

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
        if (enabled) {
            val all = dao.getAll().map { it.toDomain() }
            val proposed = existing.toDomain().copy(enabled = true)
            if (!entitlementRepository.entitlement.value.hasPremiumAccess) {
                val selected = RoutineTierPolicy.eligibleFreeRoutine(all, freeRoutineSelectionStore.selectedRoutineId.value)
                if (selected?.id != id || proposed.type != RoutineType.LINE_DIRECTION) throw RoutineTierLimitException()
            }
            when (val overlap = RoutineScheduleOverlapValidator.validate(proposed, all)) {
                RoutineOverlapValidationResult.Valid -> Unit
                is RoutineOverlapValidationResult.Overlaps -> throw RoutineScheduleOverlapException(overlap.routineId)
            }
        }
        dao.update(existing.copy(enabled = enabled))
    }

    override suspend fun hasAnyRoutine(): Boolean = dao.hasAny()
}
