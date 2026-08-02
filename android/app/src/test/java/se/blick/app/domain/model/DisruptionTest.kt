package se.blick.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Pure JVM tests for [relevantDisruptions] — expiry filtering, disruptionId- and content-based
 * de-duplication, and priority ordering, entirely independent of any repository/cache/network
 * concern. */
class DisruptionTest {

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private fun disruption(
        id: String = "d1",
        version: Int = 1,
        validUntil: Instant? = null,
        importance: Int = 1,
        influence: Int = 1,
        urgency: Int = 1,
        header: String = "Header $id",
        details: String = "Details $id",
    ) = Disruption(
        disruptionId = id,
        version = version,
        createdAt = now.minusSeconds(3600),
        modifiedAt = null,
        validFrom = null,
        validUntil = validUntil,
        priority = DisruptionPriority(importance, influence, urgency),
        message = DisruptionMessage(header, details, null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    // ---- Expiry ----

    @Test
    fun `a disruption with no validUntil is always kept, regardless of now`() {
        val d = disruption(validUntil = null)
        assertEquals(listOf(d), listOf(d).relevantDisruptions(now))
    }

    @Test
    fun `a disruption whose validUntil is in the past is dropped`() {
        val expired = disruption(validUntil = now.minusSeconds(60))
        assertEquals(emptyList<Disruption>(), listOf(expired).relevantDisruptions(now))
    }

    @Test
    fun `a disruption whose validUntil is exactly now is kept, not yet expired`() {
        val d = disruption(validUntil = now)
        assertEquals(listOf(d), listOf(d).relevantDisruptions(now))
    }

    @Test
    fun `a disruption whose validUntil is in the future is kept`() {
        val d = disruption(validUntil = now.plusSeconds(60))
        assertEquals(listOf(d), listOf(d).relevantDisruptions(now))
    }

    // ---- De-duplication ----

    @Test
    fun `duplicate disruptionIds collapse to a single entry`() {
        val a = disruption(id = "d1", version = 1)
        val b = disruption(id = "d1", version = 2)
        val result = listOf(a, b).relevantDisruptions(now)
        assertEquals(1, result.size)
    }

    @Test
    fun `the highest version wins among duplicate disruptionIds`() {
        val older = disruption(id = "d1", version = 1, header = "Old header")
        val newer = disruption(id = "d1", version = 3, header = "New header")
        val result = listOf(older, newer).relevantDisruptions(now)
        assertEquals("New header", result.single().message.header)
        assertEquals(3, result.single().version)
    }

    @Test
    fun `distinct disruptionIds are all kept`() {
        val a = disruption(id = "a")
        val b = disruption(id = "b")
        val result = listOf(a, b).relevantDisruptions(now)
        assertEquals(setOf("a", "b"), result.map { it.disruptionId }.toSet())
    }

    // ---- Content-based de-duplication (distinct disruptionIds, identical message text) ----

    @Test
    fun `two distinct disruptionIds with identical header and details collapse to one entry`() {
        val a = disruption(id = "a", header = "Delays on line 14", details = "Expect longer travel times.")
        val b = disruption(id = "b", header = "Delays on line 14", details = "Expect longer travel times.")
        val result = listOf(a, b).relevantDisruptions(now)
        assertEquals(1, result.size)
    }

    @Test
    fun `of two duplicate-text disruptions, the higher-priority one is kept`() {
        val low = disruption(id = "a", header = "Delays on line 14", details = "Expect longer travel times.", importance = 1)
        val high = disruption(id = "b", header = "Delays on line 14", details = "Expect longer travel times.", importance = 5)
        val result = listOf(low, high).relevantDisruptions(now)
        assertEquals("b", result.single().disruptionId)
    }

    @Test
    fun `disruptions with the same header but different details are not deduplicated`() {
        val a = disruption(id = "a", header = "Delays on line 14", details = "Expect longer travel times.")
        val b = disruption(id = "b", header = "Delays on line 14", details = "A different explanation.")
        val result = listOf(a, b).relevantDisruptions(now)
        assertEquals(2, result.size)
    }

    // ---- Priority ordering ----

    @Test
    fun `higher importance sorts first`() {
        val low = disruption(id = "low", importance = 1)
        val high = disruption(id = "high", importance = 3)
        val result = listOf(low, high).relevantDisruptions(now)
        assertEquals(listOf("high", "low"), result.map { it.disruptionId })
    }

    @Test
    fun `equal importance falls back to influence`() {
        val low = disruption(id = "low", importance = 2, influence = 1)
        val high = disruption(id = "high", importance = 2, influence = 3)
        val result = listOf(low, high).relevantDisruptions(now)
        assertEquals(listOf("high", "low"), result.map { it.disruptionId })
    }

    @Test
    fun `equal importance and influence falls back to urgency`() {
        val low = disruption(id = "low", importance = 2, influence = 2, urgency = 1)
        val high = disruption(id = "high", importance = 2, influence = 2, urgency = 3)
        val result = listOf(low, high).relevantDisruptions(now)
        assertEquals(listOf("high", "low"), result.map { it.disruptionId })
    }

    @Test
    fun `expiry, dedup, and ordering all apply together`() {
        val expired = disruption(id = "expired", validUntil = now.minusSeconds(1))
        val dupOld = disruption(id = "dup", version = 1, importance = 1)
        val dupNew = disruption(id = "dup", version = 2, importance = 1)
        val highPriority = disruption(id = "urgent", importance = 5)
        val result = listOf(expired, dupOld, dupNew, highPriority).relevantDisruptions(now)
        assertEquals(listOf("urgent", "dup"), result.map { it.disruptionId })
        assertEquals(2, result.first { it.disruptionId == "dup" }.version)
    }
}
