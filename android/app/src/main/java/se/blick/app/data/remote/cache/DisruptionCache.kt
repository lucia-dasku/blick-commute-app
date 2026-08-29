package se.blick.app.data.remote.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.blick.app.domain.model.DisruptionsSnapshot
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One routine's disruptions filter — the cache key. */
data class DisruptionCacheKey(val siteId: Long, val lineId: Long?, val transportMode: TransportMode?)

/**
 * Shared, process-wide cache in front of the SL Deviations-backed `/api/v1/disruptions`
 * endpoint, so that [se.blick.app.scheduling.RoutineActiveWindowWorker] (posting/refreshing the
 * ongoing notification roughly every 30 seconds) and [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel]
 * (its own ~30-second auto-refresh loop while the screen is visible) can request the exact
 * same routine's disruptions concurrently without duplicating an in-flight request. A fresh
 * response is reused only until its backend [DisruptionsSnapshot.sourceFetchedAt] reaches
 * [CACHE_TTL]; an already-stale or timestamp-invalid response uses the shorter
 * [STALE_RESPONSE_RETRY_THROTTLE] before another backend request is allowed. The backend itself
 * remains the authority enforcing SL Deviations' network-wide fair-use limit (see
 * docs/api-contract.md, "Caching and fair use").
 *
 * Two callers racing for the same key never trigger two fetches: the first to arrive starts
 * one [fetch] as a child of [scope] (a detached, [SupervisorJob]-backed scope owned by this
 * singleton, not by either caller's own [CoroutineScope]) and stores its [Deferred] under
 * [inFlight]; every other concurrent caller for the same key finds and awaits that same
 * [Deferred] instead of starting its own. Running the fetch on this class's own scope — rather
 * than directly inside whichever caller happened to arrive first — is deliberate: if that
 * first caller's own scope is cancelled (e.g. the Routine Details screen is navigated away
 * from) while the worker is still awaiting the same in-flight fetch, the fetch itself must
 * keep running for the worker's sake, not be torn down with it.
 */
@Singleton
class DisruptionCache @Inject constructor(
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val entries = mutableMapOf<DisruptionCacheKey, Entry>()
    private val inFlight = mutableMapOf<DisruptionCacheKey, Deferred<DisruptionsSnapshot>>()

    /** Source freshness describes the DATA. [nextRequestAllowedAt] independently throttles
     * backend requests after receiving an already-stale or timestamp-invalid fallback. Keeping
     * these separate prevents response arrival from relabelling old source data as fresh without
     * turning Routine Details plus the worker into a tight retry loop. */
    private data class Entry(val snapshot: DisruptionsSnapshot, val nextRequestAllowedAt: Instant)

    suspend fun getOrFetch(key: DisruptionCacheKey, fetch: suspend () -> DisruptionsSnapshot): DisruptionsSnapshot {
        val deferred = mutex.withLock {
            val now = clock.instant()
            val cached = entries[key]
            if (cached != null && now.isBefore(cached.nextRequestAllowedAt)) {
                return cached.snapshot
            }
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        val snapshot = fetch()
                        val receivedAt = clock.instant()
                        // A slightly future source timestamp can result from harmless clock skew,
                        // but must never create more than the normal freshness window on Android.
                        val boundedSourceFetchedAt = snapshot.sourceFetchedAt?.let { minOf(it, receivedAt) }
                        val sourceFreshUntil = boundedSourceFetchedAt?.plus(CACHE_TTL)
                        val nextRequestAllowedAt = sourceFreshUntil
                            ?.takeIf { it.isAfter(receivedAt) }
                            ?: receivedAt.plus(STALE_RESPONSE_RETRY_THROTTLE)
                        mutex.withLock {
                            entries[key] = Entry(snapshot, nextRequestAllowedAt)
                        }
                        snapshot
                    } finally {
                        mutex.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }

    companion object {
        /** SL's own fair-use guidance already caps the backend's upstream calls at one per
         * minute in aggregate (see docs/api-contract.md); matching that window here is what
         * keeps this app's own request volume proportionate to it, not an independently chosen
         * number. */
        val CACHE_TTL: Duration = Duration.ofSeconds(60)

        /** An already-stale or timestamp-invalid backend response is eligible for acquisition
         * again on the next ordinary active-window tick, not immediately from another caller.
         * This is request throttling only; it never changes the source data's stale status. */
        val STALE_RESPONSE_RETRY_THROTTLE: Duration = Duration.ofSeconds(30)
    }
}
