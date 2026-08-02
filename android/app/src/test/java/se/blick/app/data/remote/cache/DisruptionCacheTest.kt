package se.blick.app.data.remote.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [DisruptionCache] runs its de-duplicated fetches on its own detached, real
 * [kotlinx.coroutines.Dispatchers.Default]-backed scope (see that class's own doc on why),
 * so these tests exercise real inter-coroutine synchronization ([CompletableDeferred]) rather
 * than a virtual-time trick — but every assertion here is still deterministic: the cache's
 * internal [kotlinx.coroutines.sync.Mutex] guarantees exactly one caller ever becomes the
 * "leader" that actually invokes the fetch lambda for a given key, regardless of real
 * scheduling order, so no `delay`-based timing margin is needed anywhere below.
 */
class DisruptionCacheTest {

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")

    /** Settable [Clock] test double — unlike [Clock.fixed], lets a single test drive time
     * forward mid-test to exercise TTL expiry deterministically. */
    private class MutableClock(private var current: Instant, private val zoneId: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private fun sampleDisruption(id: String = "d1") = Disruption(
        disruptionId = id,
        version = 1,
        createdAt = now,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage("Header", "Details", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    private val key = DisruptionCacheKey(siteId = 9145, lineId = null, transportMode = null)

    // ---- TTL ----

    @Test
    fun `a cached value is reused within the TTL window without calling fetch again`() = runTest {
        val clock = MutableClock(now)
        val cache = DisruptionCache(clock)
        var callCount = 0
        val fetch: suspend () -> List<Disruption> = { callCount++; listOf(sampleDisruption()) }

        cache.getOrFetch(key, fetch)
        clock.advance(Duration.ofSeconds(59))
        cache.getOrFetch(key, fetch)

        assertEquals(1, callCount)
    }

    @Test
    fun `fetch is called again once the TTL window has elapsed`() = runTest {
        val clock = MutableClock(now)
        val cache = DisruptionCache(clock)
        var callCount = 0
        val fetch: suspend () -> List<Disruption> = { callCount++; listOf(sampleDisruption()) }

        cache.getOrFetch(key, fetch)
        clock.advance(Duration.ofSeconds(61))
        cache.getOrFetch(key, fetch)

        assertEquals(2, callCount)
    }

    @Test
    fun `a value exactly at the TTL boundary is treated as expired, refetching`() = runTest {
        val clock = MutableClock(now)
        val cache = DisruptionCache(clock)
        var callCount = 0
        val fetch: suspend () -> List<Disruption> = { callCount++; listOf(sampleDisruption()) }

        cache.getOrFetch(key, fetch)
        clock.advance(DisruptionCache.CACHE_TTL)
        cache.getOrFetch(key, fetch)

        assertEquals(2, callCount)
    }

    // ---- Per-key isolation ----

    @Test
    fun `different keys are cached and fetched independently`() = runTest {
        val cache = DisruptionCache(MutableClock(now))
        var callCount = 0
        val fetch: suspend () -> List<Disruption> = { callCount++; listOf(sampleDisruption()) }

        cache.getOrFetch(DisruptionCacheKey(9145, null, null), fetch)
        cache.getOrFetch(DisruptionCacheKey(9192, null, null), fetch)
        cache.getOrFetch(DisruptionCacheKey(9145, 14, null), fetch)

        assertEquals(3, callCount)
    }

    // ---- Concurrent de-duplication ----

    @Test
    fun `concurrent calls for the same key share one fetch and the same result`() = runTest {
        val cache = DisruptionCache(MutableClock(now))
        var callCount = 0
        val gate = CompletableDeferred<Unit>()
        val fetch: suspend () -> List<Disruption> = {
            callCount++
            gate.await()
            listOf(sampleDisruption())
        }

        // Both launched before the gate is released -- neither's fetch can complete until
        // gate.complete() below, so this deterministically proves at most one fetch call
        // happens for the two of them combined (see the class doc's mutex argument), not
        // merely that they happen to race to the same answer.
        val first = async(Dispatchers.Default) { cache.getOrFetch(key, fetch) }
        val second = async(Dispatchers.Default) { cache.getOrFetch(key, fetch) }
        gate.complete(Unit)

        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(1, callCount)
        assertEquals(firstResult, secondResult)
    }

    @Test
    fun `a caller cancelled while another awaits the same in-flight fetch does not affect the other`() = runTest {
        val cache = DisruptionCache(MutableClock(now))
        val gate = CompletableDeferred<Unit>()
        val fetch: suspend () -> List<Disruption> = {
            gate.await()
            listOf(sampleDisruption())
        }

        val leader = async(Dispatchers.Default) { cache.getOrFetch(key, fetch) }
        val follower = async(Dispatchers.Default) { cache.getOrFetch(key, fetch) }
        // The follower's own coroutine is cancelled (simulating, e.g., the Routine Details
        // screen being navigated away from) -- the underlying fetch is owned by the cache's
        // own detached scope, not by either caller's, so this must not cancel the leader.
        follower.cancel()
        gate.complete(Unit)

        assertEquals(listOf(sampleDisruption()), leader.await())
    }

    // ---- Failure isolation ----

    @Test
    fun `a failed fetch is not cached -- the next call retries`() = runTest {
        val cache = DisruptionCache(MutableClock(now))
        var callCount = 0
        val failing: suspend () -> List<Disruption> = { callCount++; throw IOException("boom") }

        try {
            cache.getOrFetch(key, failing)
            fail("expected the fetch failure to propagate")
        } catch (e: IOException) {
            // expected
        }

        val succeeding: suspend () -> List<Disruption> = { callCount++; listOf(sampleDisruption()) }
        val result = cache.getOrFetch(key, succeeding)

        assertEquals(2, callCount)
        assertEquals(listOf(sampleDisruption()), result)
    }
}
