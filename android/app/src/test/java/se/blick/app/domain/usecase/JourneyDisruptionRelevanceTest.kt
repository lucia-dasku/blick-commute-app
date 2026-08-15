package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * Pure JVM tests for [primaryDisruptionNotices] and [compactPresentation] — the two functions
 * that decide, for an exact-destination routine, which journey's disruption notices are
 * currently relevant and how they collapse into the single compact notification/widget
 * indicator. See `RoutineActiveWindowWorkerTest`'s own "Exact-destination disruption relevance"
 * section for the same behavior exercised end to end through the worker.
 */
class JourneyDisruptionRelevanceTest {

    private val now = Instant.parse("2026-08-10T22:12:00Z")

    private fun journey(id: String, role: JourneyRole, notices: List<JourneyDisruptionNotice> = emptyList()): JourneyPlan {
        val leg = JourneyLeg(TransportMode.BUS, "1", "End", "A", "B", now, now.plusSeconds(600), true, emptyList())
        return JourneyPlan(id, "A", "B", now, now.plusSeconds(600), 0, leg, listOf(leg), emptyList(), role, notices)
    }

    private fun notice(text: String, effect: DisruptionEffect = DisruptionEffect.DISRUPTION) = JourneyDisruptionNotice(text, effect)

    // ---- primaryDisruptionNotices: PRIMARY alone decides relevance ----

    @Test
    fun `returns PRIMARY's own notices`() {
        val primary = journey("p", JourneyRole.PRIMARY, listOf(notice("Delayed", DisruptionEffect.DELAYS)))
        assertEquals(listOf(notice("Delayed", DisruptionEffect.DELAYS)), listOf(primary).primaryDisruptionNotices())
    }

    @Test
    fun `NEXT's own notices never surface -- only PRIMARY's do`() {
        val primary = journey("p", JourneyRole.PRIMARY)
        val next = journey("n", JourneyRole.NEXT, listOf(notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)))
        assertEquals(emptyList<JourneyDisruptionNotice>(), listOf(primary, next).primaryDisruptionNotices())
    }

    @Test
    fun `ALTERNATIVE's own notices never surface -- only PRIMARY's do`() {
        val primary = journey("p", JourneyRole.PRIMARY)
        val alternative = journey("a", JourneyRole.ALTERNATIVE, listOf(notice("Replacement bus", DisruptionEffect.REPLACEMENT_SERVICE)))
        assertEquals(emptyList<JourneyDisruptionNotice>(), listOf(primary, alternative).primaryDisruptionNotices())
    }

    @Test
    fun `no PRIMARY at all -- an empty or failed search -- returns no notices`() {
        val next = journey("n", JourneyRole.NEXT, listOf(notice("Rerouted")))
        assertEquals(emptyList<JourneyDisruptionNotice>(), listOf(next).primaryDisruptionNotices())
        assertEquals(emptyList<JourneyDisruptionNotice>(), emptyList<JourneyPlan>().primaryDisruptionNotices())
    }

    @Test
    fun `duplicate copies of the same PRIMARY notice text are deduplicated`() {
        val primary = journey("p", JourneyRole.PRIMARY, listOf(notice("Delayed", DisruptionEffect.DELAYS), notice("Delayed", DisruptionEffect.DELAYS)))
        assertEquals(listOf(notice("Delayed", DisruptionEffect.DELAYS)), listOf(primary).primaryDisruptionNotices())
    }

    @Test
    fun `multiple genuinely different PRIMARY notices are all preserved, in order`() {
        val primary = journey(
            "p", JourneyRole.PRIMARY,
            listOf(notice("Delayed", DisruptionEffect.DELAYS), notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)),
        )
        assertEquals(
            listOf(notice("Delayed", DisruptionEffect.DELAYS), notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)),
            listOf(primary).primaryDisruptionNotices(),
        )
    }

    @Test
    fun `when PRIMARY changes between two calls, its notices change too -- a pure, uncached derivation`() {
        val firstTickPrimary = journey("first", JourneyRole.PRIMARY, listOf(notice("Delayed", DisruptionEffect.DELAYS)))
        assertEquals(listOf(notice("Delayed", DisruptionEffect.DELAYS)), listOf(firstTickPrimary).primaryDisruptionNotices())

        // A later refresh promotes a DIFFERENT journey to PRIMARY (e.g. a realtime update, or a
        // newly-discovered candidate -- see backend/src/routes/journeys.ts's own doc). Calling
        // the exact same function again, with only the input list changed, must reflect the new
        // PRIMARY immediately -- there is no memoization or leftover state from the earlier call.
        val secondTickPrimary = journey("second", JourneyRole.PRIMARY, listOf(notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)))
        assertEquals(listOf(notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)), listOf(secondTickPrimary).primaryDisruptionNotices())
    }

    // ---- compactPresentation: the single-value, conservative aggregation ----

    @Test
    fun `empty notices produce no presentation`() {
        assertNull(emptyList<JourneyDisruptionNotice>().compactPresentation())
    }

    @Test
    fun `a single notice's own real text and classified effect are carried through unchanged`() {
        val presentation = listOf(notice("Hissen är ur funktion.", DisruptionEffect.ACCESSIBILITY_ISSUE)).compactPresentation()
        assertEquals(DisruptionPresentation("Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE), presentation)
    }

    @Test
    fun `several duplicate copies of the identical notice still use that notice's own effect`() {
        val presentation = listOf(
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Delayed", DisruptionEffect.DELAYS),
        ).compactPresentation()
        assertEquals(DisruptionPresentation("Delayed", null, DisruptionEffect.DELAYS), presentation)
    }

    @Test
    fun `multiple genuinely different notices fall back to the generic DISRUPTION effect -- never an invented ranking`() {
        val presentation = listOf(
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Rerouted", DisruptionEffect.ROUTE_CHANGE),
        ).compactPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        // The headline is still real, first-occurrence text -- never replaced by a label -- even
        // though the effect itself is the generic fallback.
        assertEquals("Delayed", presentation?.headline)
    }
}
