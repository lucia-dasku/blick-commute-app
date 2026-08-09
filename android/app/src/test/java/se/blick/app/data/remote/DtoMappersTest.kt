package se.blick.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.data.remote.dto.DisruptionDto
import se.blick.app.data.remote.dto.DisruptionMessageDto
import se.blick.app.data.remote.dto.DisruptionPriorityDto
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.toDisruptionEffect

/**
 * Forward/backward compatibility for [DisruptionDto.effect] ↔ [DisruptionEffect] — the one part
 * of [DisruptionDto.toDomain] this suite exercises; the rest of `DtoMappers.kt` is covered where
 * its callers already are (`GetDisruptionsUseCaseTest`, `DisruptionCacheTest`, etc.), not
 * duplicated here. Matches the same JSON configuration `di/NetworkModule.kt` actually provides
 * (`ignoreUnknownKeys = true`) so "an old backend response is missing this field entirely" is
 * tested against the real wire format, not just a Kotlin default-parameter value that happens to
 * agree with it.
 */
class DtoMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun priority() = DisruptionPriorityDto(importance = 1, influence = 1, urgency = 1)
    private fun message(header: String = "Header") = DisruptionMessageDto(header = header, details = "Details", language = "sv")

    private fun dto(effect: String) = DisruptionDto(
        disruptionId = "d1",
        version = 1,
        createdAt = "2026-07-28T08:00:00Z",
        priority = priority(),
        message = message(),
        effect = effect,
    )

    // ---- String.toDisruptionEffect() -- the pure mapping function ----

    @Test
    fun `a recognized backend value maps to its corresponding DisruptionEffect`() {
        assertEquals(DisruptionEffect.DELAYS, "DELAYS".toDisruptionEffect())
        assertEquals(DisruptionEffect.NO_SERVICE, "NO_SERVICE".toDisruptionEffect())
        assertEquals(DisruptionEffect.REDUCED_SERVICE, "REDUCED_SERVICE".toDisruptionEffect())
        assertEquals(DisruptionEffect.ROUTE_CHANGE, "ROUTE_CHANGE".toDisruptionEffect())
        assertEquals(DisruptionEffect.STOP_CHANGE, "STOP_CHANGE".toDisruptionEffect())
        assertEquals(DisruptionEffect.REPLACEMENT_SERVICE, "REPLACEMENT_SERVICE".toDisruptionEffect())
        assertEquals(DisruptionEffect.STATION_ACCESS, "STATION_ACCESS".toDisruptionEffect())
        assertEquals(DisruptionEffect.ACCESSIBILITY_ISSUE, "ACCESSIBILITY_ISSUE".toDisruptionEffect())
        assertEquals(DisruptionEffect.DISRUPTION, "DISRUPTION".toDisruptionEffect())
    }

    @Test
    fun `an unknown future backend value falls back to DISRUPTION rather than throwing`() {
        assertEquals(DisruptionEffect.DISRUPTION, "SOME_FUTURE_EFFECT".toDisruptionEffect())
    }

    @Test
    fun `an empty string falls back to DISRUPTION`() {
        assertEquals(DisruptionEffect.DISRUPTION, "".toDisruptionEffect())
    }

    @Test
    fun `wrong case is treated as unknown, not case-insensitively matched, and falls back to DISRUPTION`() {
        // enum valueOf is exact-match only -- documenting that deliberately, since a backend
        // that ever sent lowercase would otherwise silently regress to DISRUPTION for everyone.
        assertEquals(DisruptionEffect.DISRUPTION, "delays".toDisruptionEffect())
    }

    // ---- DisruptionDto.toDomain() -- the field actually wired end to end ----

    @Test
    fun `DELAYS on the DTO maps to DisruptionEffect DELAYS on the domain model`() {
        assertEquals(DisruptionEffect.DELAYS, dto("DELAYS").toDomain().effect)
    }

    @Test
    fun `an unknown effect value on the DTO maps to DisruptionEffect DISRUPTION on the domain model`() {
        assertEquals(DisruptionEffect.DISRUPTION, dto("SOME_FUTURE_EFFECT").toDomain().effect)
    }

    @Test
    fun `constructing the DTO without specifying effect defaults to the DISRUPTION wire value`() {
        val withoutEffect = DisruptionDto(disruptionId = "d1", version = 1, createdAt = "2026-07-28T08:00:00Z", priority = priority(), message = message())
        assertEquals("DISRUPTION", withoutEffect.effect)
        assertEquals(DisruptionEffect.DISRUPTION, withoutEffect.toDomain().effect)
    }

    @Test
    fun `every other Disruption field is still correctly mapped alongside effect`() {
        val domain = dto("STATION_ACCESS").toDomain()
        assertEquals("d1", domain.disruptionId)
        assertEquals("Header", domain.message.header)
        assertEquals(DisruptionEffect.STATION_ACCESS, domain.effect)
    }

    // ---- Real JSON wire format -- proves the compatibility contract against actual
    // deserialization, not just Kotlin default-parameter behavior ----

    @Test
    fun `old backend + new Android -- a real JSON response with no effect key at all decodes to DISRUPTION`() {
        val responseJson = """
            {
              "disruptionId": "d1",
              "version": 1,
              "createdAt": "2026-07-28T08:00:00Z",
              "priority": {"importance": 1, "influence": 1, "urgency": 1},
              "message": {"header": "Header", "details": "Details", "language": "sv"}
            }
        """.trimIndent()
        val decoded = json.decodeFromString<DisruptionDto>(responseJson)
        assertEquals("DISRUPTION", decoded.effect)
        assertEquals(DisruptionEffect.DISRUPTION, decoded.toDomain().effect)
    }

    @Test
    fun `new backend + new Android -- a real JSON response with a known effect decodes correctly`() {
        val responseJson = """
            {
              "disruptionId": "d1",
              "version": 1,
              "createdAt": "2026-07-28T08:00:00Z",
              "priority": {"importance": 1, "influence": 1, "urgency": 1},
              "effect": "DELAYS",
              "message": {"header": "Header", "details": "Details", "language": "sv"}
            }
        """.trimIndent()
        val decoded = json.decodeFromString<DisruptionDto>(responseJson)
        assertEquals("DELAYS", decoded.effect)
        assertEquals(DisruptionEffect.DELAYS, decoded.toDomain().effect)
    }

    @Test
    fun `an unrecognized effect value from a future backend still decodes the whole response, falling back to DISRUPTION for that one field`() {
        val responseJson = """
            {
              "disruptionId": "d1",
              "version": 1,
              "createdAt": "2026-07-28T08:00:00Z",
              "priority": {"importance": 1, "influence": 1, "urgency": 1},
              "effect": "SOME_BRAND_NEW_EFFECT_THIS_BUILD_HAS_NEVER_SEEN",
              "message": {"header": "Header", "details": "Details", "language": "sv"}
            }
        """.trimIndent()
        val decoded = json.decodeFromString<DisruptionDto>(responseJson)
        assertEquals(DisruptionEffect.DISRUPTION, decoded.toDomain().effect)
    }
}
