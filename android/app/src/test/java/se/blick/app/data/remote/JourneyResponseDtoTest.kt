package se.blick.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.data.remote.dto.JourneyContextDto
import se.blick.app.data.remote.dto.JourneySearchModeDto
import se.blick.app.data.remote.dto.JourneysResponseDto

class JourneyResponseDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `planned response context and request metadata parse explicitly`() {
        val response = json.decodeFromString<JourneysResponseDto>(
            """{"fetchedAt":"2026-08-10T12:00:00Z","journeys":[],"journeyContext":"PLANNED","searchMode":"ARRIVE_BY","requestedDateTime":"2026-08-10T16:30:00.000Z"}""",
        )

        assertEquals(JourneyContextDto.PLANNED, response.journeyContext)
        assertEquals(JourneySearchModeDto.ARRIVE_BY, response.searchMode)
        assertEquals("2026-08-10T16:30:00.000Z", response.requestedDateTime)
    }

    @Test
    fun `live response context parses explicitly`() {
        val response = json.decodeFromString<JourneysResponseDto>(
            """{"fetchedAt":"2026-08-10T12:00:00Z","journeys":[],"journeyContext":"LIVE","searchMode":"NOW","requestedDateTime":null}""",
        )

        assertEquals(JourneyContextDto.LIVE, response.journeyContext)
        assertEquals(JourneySearchModeDto.NOW, response.searchMode)
        assertNull(response.requestedDateTime)
    }
}
