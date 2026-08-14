package se.blick.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.JourneyLegDto
import se.blick.app.data.remote.dto.JourneyPlanDto
import se.blick.app.data.remote.dto.JourneysResponseDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES
import se.blick.app.domain.model.JourneyRole

/**
 * Backend roles are authoritative — see [se.blick.app.domain.model.toJourneyRole]'s own doc.
 * Proves [RemoteJourneyRepository] fails CLOSED on a role it cannot establish: null, unknown,
 * or malformed never silently becomes [JourneyRole.PRIMARY] (or any other role) — the whole
 * journey is dropped from the mapped result instead, while every OTHER, validly-roled journey
 * in the same response is still returned.
 */
class JourneyRepositoryTest {

    private fun leg(name: String = "14") = JourneyLegDto(
        transportMode = "METRO",
        lineDesignation = name,
        direction = "Direction",
        originName = "Origin",
        destinationName = "Destination",
        departureTime = "2026-08-10T08:00:00Z",
        arrivalTime = "2026-08-10T08:20:00Z",
        isRealtime = true,
    )

    private fun dto(id: String, role: String?) = JourneyPlanDto(
        journeyId = id,
        originName = "Origin",
        destinationName = "Destination",
        departureTime = "2026-08-10T08:00:00Z",
        arrivalTime = "2026-08-10T08:20:00Z",
        transferCount = 0,
        firstLeg = leg(),
        legs = listOf(leg()),
        role = role,
    )

    private class FakeApiClient(private val response: JourneysResponseDto) : BlickApiClient {
        override suspend fun searchStops(query: String): StopSearchResponseDto = throw NotImplementedError("unused")
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResponseDto = throw NotImplementedError("unused")
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto =
            throw NotImplementedError("unused")
        override suspend fun getJourneys(originId: String, destinationId: String, transportModes: String, searchUntil: String?) = response
    }

    private suspend fun mapped(vararg journeys: JourneyPlanDto) =
        RemoteJourneyRepository(FakeApiClient(JourneysResponseDto("2026-08-10T07:00:00Z", journeys.toList())))
            .getJourneys("origin", "destination", DEFAULT_JOURNEY_TRANSPORT_MODES)

    @Test
    fun `each valid role maps through unchanged`() = runTest {
        val result = mapped(dto("primary", "PRIMARY"), dto("next", "NEXT"), dto("alternative", "ALTERNATIVE"))

        assertEquals(
            mapOf("primary" to JourneyRole.PRIMARY, "next" to JourneyRole.NEXT, "alternative" to JourneyRole.ALTERNATIVE),
            result.associate { it.journeyId to it.role },
        )
    }

    @Test
    fun `a null role drops the journey entirely rather than defaulting to PRIMARY`() = runTest {
        val result = mapped(dto("malformed", null), dto("primary", "PRIMARY"))

        assertEquals(listOf("primary"), result.map { it.journeyId })
        assertTrue(result.none { it.journeyId == "malformed" })
    }

    @Test
    fun `an unrecognized role string drops the journey entirely rather than defaulting to PRIMARY`() = runTest {
        val result = mapped(dto("malformed", "SOMETHING_UNKNOWN"), dto("next", "NEXT"))

        assertEquals(listOf("next"), result.map { it.journeyId })
    }

    @Test
    fun `an empty-string role drops the journey entirely rather than defaulting to PRIMARY`() = runTest {
        val result = mapped(dto("malformed", ""), dto("alternative", "ALTERNATIVE"))

        assertEquals(listOf("alternative"), result.map { it.journeyId })
    }

    @Test
    fun `a lowercase role string is treated as malformed, never coerced to a match`() = runTest {
        // Backend roles are always emitted upper-case -- valueOf is case-sensitive, and a
        // lowercase string must fail closed exactly like any other unrecognized value.
        val result = mapped(dto("malformed", "primary"))

        assertEquals(emptyList<String>(), result.map { it.journeyId })
    }

    @Test
    fun `one malformed journey among otherwise-valid ones only drops that entry, never the whole response`() = runTest {
        val result = mapped(dto("primary", "PRIMARY"), dto("malformed", "not-a-role"), dto("next", "NEXT"))

        assertEquals(listOf("primary", "next"), result.map { it.journeyId })
    }
}
