package se.blick.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import se.blick.app.domain.model.OneTimeEvent

class OneTimeEventPremiumRequiredException : IllegalStateException("One-time events require Blick Premium")
class OneTimeEventInPastException : IllegalArgumentException("One-time event must be in the future")

interface OneTimeEventRepository {
    fun observeAll(): Flow<List<OneTimeEvent>>
    suspend fun getById(id: String): OneTimeEvent?
    suspend fun save(event: OneTimeEvent)
    suspend fun delete(id: String)
}

object EmptyOneTimeEventRepository : OneTimeEventRepository {
    override fun observeAll(): Flow<List<OneTimeEvent>> = flowOf(emptyList())
    override suspend fun getById(id: String): OneTimeEvent? = null
    override suspend fun save(event: OneTimeEvent) = Unit
    override suspend fun delete(id: String) = Unit
}
