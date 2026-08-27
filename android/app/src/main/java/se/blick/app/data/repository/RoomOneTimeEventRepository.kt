package se.blick.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.local.room.OneTimeEventDao
import se.blick.app.data.local.room.toDomain
import se.blick.app.data.local.room.toEntity
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.scheduling.OneTimeEventScheduler
import javax.inject.Inject
import java.time.Clock

class RoomOneTimeEventRepository @Inject constructor(
    private val dao: OneTimeEventDao,
    private val entitlementRepository: PremiumEntitlementRepository,
    private val scheduler: OneTimeEventScheduler,
    private val clock: Clock,
) : OneTimeEventRepository {
    override fun observeAll(): Flow<List<OneTimeEvent>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: String): OneTimeEvent? = dao.getById(id)?.toDomain()

    override suspend fun save(event: OneTimeEvent) {
        if (!entitlementRepository.entitlement.value.hasPremiumAccess) {
            throw OneTimeEventPremiumRequiredException()
        }
        if (event.targetInstant() <= clock.instant()) throw OneTimeEventInPastException()
        require(event.originId != event.destinationId) { "Origin and destination must differ" }
        dao.upsert(event.toEntity())
        scheduler.schedule(event)
    }

    override suspend fun delete(id: String) {
        scheduler.cancel(id)
        dao.deleteById(id)
    }
}
