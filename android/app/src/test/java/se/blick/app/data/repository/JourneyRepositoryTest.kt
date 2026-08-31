package se.blick.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.JourneyDisruptionContextDto
import se.blick.app.data.remote.dto.JourneyDisruptionContextLegDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceRequestDto
import se.blick.app.data.remote.dto.JourneyDisruptionRelevanceResponseDto
import se.blick.app.data.remote.dto.JourneyLegDto
import se.blick.app.data.remote.dto.JourneyPlanDto
import se.blick.app.data.remote.dto.JourneysResponseDto
import se.blick.app.data.remote.dto.JourneyContextDto
import se.blick.app.data.remote.dto.JourneySearchModeDto
import se.blick.app.data.remote.dto.StopSearchResponseDto
import se.blick.app.domain.model.DEFAULT_JOURNEY_TRANSPORT_MODES
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyDisruptionContext
import se.blick.app.domain.model.JourneyDisruptionContextLeg
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.PlannedJourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            transportModes: String,
            searchUntil: String?,
            changesPreference: String,
        ) = response
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

    // ---- changesPreference: forwarded to the API client as its own enum name, never inspected
    // or acted on by RemoteJourneyRepository itself -- the backend is the sole authority on which
    // journeys are eligible under a given preference. ----

    private class CapturingApiClient(private val response: JourneysResponseDto) : BlickApiClient {
        var receivedChangesPreference: String? = null
            private set
        override suspend fun searchStops(query: String): StopSearchResponseDto = throw NotImplementedError("unused")
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResponseDto = throw NotImplementedError("unused")
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto =
            throw NotImplementedError("unused")
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            transportModes: String,
            searchUntil: String?,
            changesPreference: String,
        ): JourneysResponseDto {
            receivedChangesPreference = changesPreference
            return response
        }
    }

    @Test
    fun `changesPreference is forwarded to the API client as its own enum name`() = runTest {
        val client = CapturingApiClient(JourneysResponseDto("2026-08-10T07:00:00Z", emptyList()))

        RemoteJourneyRepository(client).getJourneys(
            "origin", "destination", DEFAULT_JOURNEY_TRANSPORT_MODES, null, ExactDestinationChangesPreference.WITH_CHANGES_ONLY,
        )

        assertEquals("WITH_CHANGES_ONLY", client.receivedChangesPreference)
    }

    @Test
    fun `changesPreference defaults to BOTH for a caller predating this parameter`() = runTest {
        val client = CapturingApiClient(JourneysResponseDto("2026-08-10T07:00:00Z", emptyList()))

        RemoteJourneyRepository(client).getJourneys("origin", "destination", DEFAULT_JOURNEY_TRANSPORT_MODES)

        assertEquals("BOTH", client.receivedChangesPreference)
    }

    private class CapturingPlannedApiClient : BlickApiClient {
        var receivedMode: JourneySearchModeDto? = null
        var receivedDateTime: String? = null
        var responseContext = JourneyContextDto.PLANNED
        var responseMode: JourneySearchModeDto? = null
        var responseJourneys: List<JourneyPlanDto> = emptyList()

        override suspend fun searchStops(query: String): StopSearchResponseDto = throw NotImplementedError("unused")
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResponseDto = throw NotImplementedError("unused")
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?): DisruptionsResponseDto =
            throw NotImplementedError("unused")

        override suspend fun getPlannedJourneys(
            originId: String,
            destinationId: String,
            transportModes: String,
            searchMode: JourneySearchModeDto,
            requestedDateTime: String,
            changesPreference: String,
        ): JourneysResponseDto {
            receivedMode = searchMode
            receivedDateTime = requestedDateTime
            return JourneysResponseDto(
                fetchedAt = "2026-01-01T00:00:00Z",
                journeys = responseJourneys,
                journeyContext = responseContext,
                searchMode = responseMode ?: searchMode,
                requestedDateTime = java.time.OffsetDateTime.parse(requestedDateTime).toInstant().toString(),
            )
        }
    }

    @Test
    fun `ARRIVE_BY serializes a Stockholm summer event with the correct offset`() = runTest {
        val client = CapturingPlannedApiClient()

        RemoteJourneyRepository(client).getPlannedJourneys(
            originId = "origin",
            destinationId = "destination",
            allowedTransportModes = DEFAULT_JOURNEY_TRANSPORT_MODES,
            searchMode = JourneySearchMode.ARRIVE_BY,
            requestedDateTime = LocalDate.of(2026, 8, 10).atTime(LocalTime.of(18, 30))
                .atZone(ZoneId.of("Europe/Stockholm")),
        )

        assertEquals(JourneySearchModeDto.ARRIVE_BY, client.receivedMode)
        assertEquals("2026-08-10T18:30+02:00", client.receivedDateTime)
    }

    @Test
    fun `LEAVE_AT serializes a Stockholm winter event with the correct offset`() = runTest {
        val client = CapturingPlannedApiClient()

        RemoteJourneyRepository(client).getPlannedJourneys(
            originId = "origin",
            destinationId = "destination",
            allowedTransportModes = DEFAULT_JOURNEY_TRANSPORT_MODES,
            searchMode = JourneySearchMode.LEAVE_AT,
            requestedDateTime = LocalDate.of(2026, 12, 10).atTime(LocalTime.of(8, 15))
                .atZone(ZoneId.of("Europe/Stockholm")),
        )

        assertEquals(JourneySearchModeDto.LEAVE_AT, client.receivedMode)
        assertEquals("2026-12-10T08:15+01:00", client.receivedDateTime)
    }

    @Test
    fun `planned roles map independently from live roles and preserve backend order`() = runTest {
        val client = CapturingPlannedApiClient().apply {
            responseJourneys = listOf(
                dto("earlier", "EARLIER"),
                dto("recommended", "RECOMMENDED"),
                dto("later", "LATER"),
            )
        }

        val result = RemoteJourneyRepository(client).getPlannedJourneys(
            originId = "origin",
            destinationId = "destination",
            allowedTransportModes = DEFAULT_JOURNEY_TRANSPORT_MODES,
            searchMode = JourneySearchMode.ARRIVE_BY,
            requestedDateTime = LocalDate.of(2026, 8, 10).atTime(18, 30)
                .atZone(ZoneId.of("Europe/Stockholm")),
        )

        assertEquals(
            listOf(PlannedJourneyRole.EARLIER, PlannedJourneyRole.RECOMMENDED, PlannedJourneyRole.LATER),
            result.choices.map { it.role },
        )
        assertEquals(listOf("earlier", "recommended", "later"), result.choices.map { it.journey.journeyId })
    }

    @Test
    fun `planned mapping drops live and malformed roles rather than reinterpreting them`() = runTest {
        val client = CapturingPlannedApiClient().apply {
            responseJourneys = listOf(dto("live-primary", "PRIMARY"), dto("bad", null), dto("recommended", "RECOMMENDED"))
        }

        val result = RemoteJourneyRepository(client).getPlannedJourneys(
            originId = "origin",
            destinationId = "destination",
            allowedTransportModes = DEFAULT_JOURNEY_TRANSPORT_MODES,
            searchMode = JourneySearchMode.LEAVE_AT,
            requestedDateTime = LocalDate.of(2026, 8, 10).atTime(18, 30)
                .atZone(ZoneId.of("Europe/Stockholm")),
        )

        assertEquals(listOf("recommended"), result.choices.map { it.journey.journeyId })
    }

    @Test
    fun `planned search rejects a LIVE response instead of rendering it as planned`() = runTest {
        val client = CapturingPlannedApiClient().apply { responseContext = JourneyContextDto.LIVE }

        val failure = runCatching {
            RemoteJourneyRepository(client).getPlannedJourneys(
                "origin",
                "destination",
                DEFAULT_JOURNEY_TRANSPORT_MODES,
                JourneySearchMode.ARRIVE_BY,
                LocalDate.of(2026, 8, 10).atTime(18, 30).atZone(ZoneId.of("Europe/Stockholm")),
            )
        }.exceptionOrNull()

        assertTrue(failure is UnexpectedJourneyContextException)
    }

    @Test
    fun `live search rejects a PLANNED response`() = runTest {
        val response = JourneysResponseDto(
            fetchedAt = "2026-08-10T07:00:00Z",
            journeys = emptyList(),
            journeyContext = JourneyContextDto.PLANNED,
            searchMode = JourneySearchModeDto.ARRIVE_BY,
            requestedDateTime = "2026-08-10T16:30:00Z",
        )

        val failure = runCatching {
            RemoteJourneyRepository(FakeApiClient(response))
                .getJourneys("origin", "destination", DEFAULT_JOURNEY_TRANSPORT_MODES)
        }.exceptionOrNull()

        assertTrue(failure is UnexpectedJourneyContextException)
    }

    // ---- disruptionContext: additive structural metadata carried opaquely between
    // /api/v1/journeys and POST /api/v1/journeys/disruptions -- Android never reads an
    // individual field out of it, only maps it 1:1 between DTO and domain shape and sends it
    // back unchanged. See backend/src/models/journeyDisruptionContext.ts's own doc. ----

    private fun disruptionContextDto() = JourneyDisruptionContextDto(
        version = 1,
        journeyStart = "Akalla",
        journeyEnd = "T-Centralen",
        legs = listOf(
            JourneyDisruptionContextLegDto(
                transportMode = "METRO",
                lineDesignation = "11",
                boardingPatternPointGid = "9025001000003272",
                alightingPatternPointGid = "9025001000003051",
                stopPatternPointGids = listOf("9025001000003272", "9025001000003051"),
                stopSequenceComplete = true,
            ),
        ),
    )

    @Test
    fun `disruptionContext maps from the DTO to the domain model with every field intact`() = runTest {
        val result = mapped(dto("primary", "PRIMARY").copy(disruptionContext = disruptionContextDto()))

        val context = result.single().disruptionContext
        assertEquals(1, context?.version)
        assertEquals("Akalla", context?.journeyStart)
        assertEquals("T-Centralen", context?.journeyEnd)
        val leg = context?.legs?.single()
        assertEquals("METRO", leg?.transportMode)
        assertEquals("11", leg?.lineDesignation)
        assertEquals("9025001000003272", leg?.boardingPatternPointGid)
        assertEquals("9025001000003051", leg?.alightingPatternPointGid)
        assertEquals(listOf("9025001000003272", "9025001000003051"), leg?.stopPatternPointGids)
        assertEquals(true, leg?.stopSequenceComplete)
    }

    @Test
    fun `disruptionContext is null when the response omits it (a stale cached-proxied deployment)`() = runTest {
        val result = mapped(dto("primary", "PRIMARY"))

        assertNull(result.single().disruptionContext)
    }

    private class CapturingDisruptionRelevanceApiClient : BlickApiClient {
        var receivedRequest: JourneyDisruptionRelevanceRequestDto? = null
            private set
        override suspend fun searchStops(query: String) = throw NotImplementedError("unused")
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?) = throw NotImplementedError("unused")
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: String?) = throw NotImplementedError("unused")
        override suspend fun getJourneyDisruptionRelevance(request: JourneyDisruptionRelevanceRequestDto): JourneyDisruptionRelevanceResponseDto {
            receivedRequest = request
            return JourneyDisruptionRelevanceResponseDto("2026-08-10T07:00:00Z", emptyList())
        }
    }

    private fun leg(originName: String, destinationName: String, lineDesignation: String) = JourneyLeg(
        TransportMode.METRO, lineDesignation, "Direction", originName, destinationName,
        Instant.parse("2026-08-16T10:00:00Z"), Instant.parse("2026-08-16T10:20:00Z"), true, emptyList(),
    )

    @Test
    fun `getRelevantDeviationNotices sends disruptionContext and departureTime-arrivalTime completely unchanged`() = runTest {
        val client = CapturingDisruptionRelevanceApiClient()
        val context = JourneyDisruptionContext(
            version = 1, journeyStart = "Akalla", journeyEnd = "T-Centralen",
            legs = listOf(
                JourneyDisruptionContextLeg(
                    "METRO", "11", "9025001000003272", "9025001000003051",
                    listOf("9025001000003272", "9025001000003051"), true,
                ),
            ),
        )

        RemoteJourneyRepository(client).getRelevantDeviationNotices(
            legs = listOf(leg("Akalla", "T-Centralen", "11")),
            originSiteId = 9192L,
            journeyPlannerNotices = emptyList(),
            disruptionContext = context,
            departureTime = Instant.parse("2026-08-16T10:00:00Z"),
            arrivalTime = Instant.parse("2026-08-16T10:20:00Z"),
        )

        val request = requireNotNull(client.receivedRequest)
        assertEquals(1, request.disruptionContext?.version)
        assertEquals("Akalla", request.disruptionContext?.journeyStart)
        assertEquals("9025001000003272", request.disruptionContext?.legs?.single()?.boardingPatternPointGid)
        assertEquals(true, request.disruptionContext?.legs?.single()?.stopSequenceComplete)
        assertEquals("2026-08-16T10:00:00Z", request.departureTime)
        assertEquals("2026-08-16T10:20:00Z", request.arrivalTime)
    }

    @Test
    fun `getRelevantDeviationNotices omits disruptionContext-departureTime-arrivalTime when the caller does not supply them`() = runTest {
        val client = CapturingDisruptionRelevanceApiClient()

        RemoteJourneyRepository(client).getRelevantDeviationNotices(
            legs = listOf(leg("Akalla", "T-Centralen", "11")),
            originSiteId = 9192L,
            journeyPlannerNotices = emptyList(),
        )

        val request = requireNotNull(client.receivedRequest)
        assertNull(request.disruptionContext)
        assertNull(request.departureTime)
        assertNull(request.arrivalTime)
    }
}
