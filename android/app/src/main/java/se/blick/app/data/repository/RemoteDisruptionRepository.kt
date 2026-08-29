package se.blick.app.data.repository

import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.cache.DisruptionCache
import se.blick.app.data.remote.cache.DisruptionCacheKey
import se.blick.app.data.remote.toDomain
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.relevantDisruptions
import java.time.Clock
import javax.inject.Inject

/**
 * Fetches through the shared [DisruptionCache] — see that class's own doc for why this is
 * the one seam that keeps concurrent callers (the active-window worker and the Routine
 * Details screen) on one shared in-flight request and one source-timestamp-aware cached
 * response — then applies [relevantDisruptions] to the (possibly briefly stale) result so every
 * caller always sees the current, de-duplicated, priority-ordered list as of right now,
 * never a snapshot that merely happened to be true up to [DisruptionCache.CACHE_TTL] ago.
 */
class RemoteDisruptionRepository @Inject constructor(
    private val apiClient: BlickApiClient,
    private val cache: DisruptionCache,
    private val clock: Clock,
) : DisruptionRepository {
    override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
        val key = DisruptionCacheKey(siteId, lineId, transportMode)
        val snapshot = cache.getOrFetch(key) {
            apiClient.getDisruptions(siteId, lineId, transportMode?.name).toDomain()
        }
        return snapshot.disruptions.relevantDisruptions(clock.instant())
    }
}
