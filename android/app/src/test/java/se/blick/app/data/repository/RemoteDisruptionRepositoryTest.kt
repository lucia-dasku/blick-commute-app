package se.blick.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.cache.DisruptionCache
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionDto
import se.blick.app.data.remote.dto.DisruptionMessageDto
import se.blick.app.data.remote.dto.DisruptionPriorityDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Proves [RemoteDisruptionRepository] wires the two pieces it sits between correctly: it
 * fetches through the shared [DisruptionCache] (so repeated calls within the TTL don't hit
 * the API client again — [DisruptionCache]'s own test covers the cache's internal behavior
 * in full detail; this only checks the wiring), and it applies
 * [se.blick.app.domain.model.relevantDisruptions] (expiry filtering + priority ordering) to
 * whatever the cache returns, every call, using the current instant — not the cache's own
 * fetch-time instant.
 */
class RemoteDisruptionRepositoryTest {

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun dto(id: String, validUntil: String? = null, importance: Int = 1) = DisruptionDto(
        disruptionId = id,
        version = 1,
        createdAt = "2026-07-27T20:12:47.15+02:00",
        modifiedAt = null,
        validFrom = null,
        validUntil = validUntil,
        priority = DisruptionPriorityDto(importance, 1, 1),
        message = DisruptionMessageDto("Header $id", "Details $id", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    private class FakeApiClient(private val response: () -> DisruptionsResponseDto) : BlickApiClient {
        var callCount = 0
        override suspend fun searchStops(query: String): StopSearchResponseDto = throw NotImplementedError("unused")
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResponseDto = throw NotImplementedError("unused")
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto {
            callCount++
            return response()
        }
    }

    private fun repository(apiClient: BlickApiClient, cache: DisruptionCache = DisruptionCache(clock)) =
        RemoteDisruptionRepository(apiClient, cache, clock)

    @Test
    fun `fetches through the cache -- a second call within the TTL does not call the API client again`() = runTest {
        val apiClient = FakeApiClient { DisruptionsResponseDto("2026-07-28T08:00:00Z", listOf(dto("d1"))) }
        val repo = repository(apiClient)

        repo.getDisruptions(9145, null, null)
        repo.getDisruptions(9145, null, null)

        assertEquals(1, apiClient.callCount)
    }

    @Test
    fun `an expired disruption is filtered out of the returned list`() = runTest {
        val apiClient = FakeApiClient {
            DisruptionsResponseDto(
                "2026-07-28T08:00:00Z",
                listOf(dto("expired", validUntil = "2026-07-01T00:00:00+02:00"), dto("current")),
            )
        }
        val repo = repository(apiClient)

        val result = repo.getDisruptions(9145, null, null)

        assertEquals(listOf("current"), result.map { it.disruptionId })
    }

    @Test
    fun `results are ordered by priority, highest importance first`() = runTest {
        val apiClient = FakeApiClient {
            DisruptionsResponseDto("2026-07-28T08:00:00Z", listOf(dto("low", importance = 1), dto("high", importance = 5)))
        }
        val repo = repository(apiClient)

        val result = repo.getDisruptions(9145, null, null)

        assertEquals(listOf("high", "low"), result.map { it.disruptionId })
    }

    @Test
    fun `siteId, lineId, and transportMode are forwarded to the API client unchanged`() = runTest {
        var receivedSiteId: Long? = null
        var receivedLineId: Long? = null
        var receivedTransportMode: String? = null
        val apiClient = object : BlickApiClient {
            override suspend fun searchStops(query: String) = throw NotImplementedError("unused")
            override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?) = throw NotImplementedError("unused")
            override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto {
                receivedSiteId = siteId
                receivedLineId = lineId
                receivedTransportMode = transportMode
                return DisruptionsResponseDto("2026-07-28T08:00:00Z", emptyList())
            }
        }
        repository(apiClient).getDisruptions(9145, 14, TransportMode.METRO)

        assertEquals(9145L, receivedSiteId)
        assertEquals(14L, receivedLineId)
        assertEquals("METRO", receivedTransportMode)
    }
}
