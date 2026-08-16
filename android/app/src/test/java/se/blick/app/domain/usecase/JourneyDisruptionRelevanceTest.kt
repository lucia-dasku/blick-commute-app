package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.DisruptionRelevance
import se.blick.app.domain.model.DisruptionSource
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * Pure JVM tests for [primaryDisruptionNotices], [compactJourneyPlannerPresentation], and
 * [compactPresentation] — the functions that decide, for an exact-destination routine, which
 * journey's disruption notices are currently relevant and how they collapse into the single
 * compact notification/widget indicator. See `RoutineActiveWindowWorkerTest`'s own
 * "Exact-destination disruption relevance" and "Exact-destination: resolved disruption
 * relevance" sections for the same behavior exercised end to end through the worker's own
 * two-phase (primary-first, deviations-second) posting.
 */
class JourneyDisruptionRelevanceTest {

    private val now = Instant.parse("2026-08-10T22:12:00Z")

    private fun journey(id: String, role: JourneyRole, notices: List<JourneyDisruptionNotice> = emptyList()): JourneyPlan {
        val leg = JourneyLeg(TransportMode.BUS, "1", "End", "A", "B", now, now.plusSeconds(600), true, emptyList())
        return JourneyPlan(id, "A", "B", now, now.plusSeconds(600), 0, leg, listOf(leg), emptyList(), role, notices)
    }

    private fun notice(text: String, effect: DisruptionEffect = DisruptionEffect.DISRUPTION) = JourneyDisruptionNotice(text, effect)

    private fun resolved(
        id: String? = "d1",
        headline: String = "Delayed",
        details: String? = null,
        effect: DisruptionEffect = DisruptionEffect.DELAYS,
        relevance: DisruptionRelevance = DisruptionRelevance.CONFIRMED,
        source: DisruptionSource = DisruptionSource.SL_DEVIATIONS,
        matchedLineDesignations: List<String> = emptyList(),
    ) = ResolvedJourneyDisruption(id, headline, details, effect, relevance, source, matchedLineDesignations)

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

    // ---- compactJourneyPlannerPresentation: the single-value, conservative aggregation of
    // Journey Planner notices ALONE (the worker's FIRST, primary-only post) -- named distinctly
    // from the List<ResolvedJourneyDisruption> overload below since the two would otherwise
    // collide at the JVM level (see that function's own doc). ----

    @Test
    fun `empty notices produce no presentation`() {
        assertNull(emptyList<JourneyDisruptionNotice>().compactJourneyPlannerPresentation())
    }

    @Test
    fun `a single notice's own real text and classified effect are carried through unchanged`() {
        val presentation = listOf(notice("Hissen är ur funktion.", DisruptionEffect.ACCESSIBILITY_ISSUE)).compactJourneyPlannerPresentation()
        assertEquals(DisruptionPresentation("Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE), presentation)
    }

    @Test
    fun `several duplicate copies of the identical notice still use that notice's own effect`() {
        val presentation = listOf(
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Delayed", DisruptionEffect.DELAYS),
        ).compactJourneyPlannerPresentation()
        assertEquals(DisruptionPresentation("Delayed", null, DisruptionEffect.DELAYS), presentation)
    }

    @Test
    fun `multiple genuinely different notices fall back to the generic DISRUPTION effect -- never an invented ranking`() {
        val presentation = listOf(
            notice("Delayed", DisruptionEffect.DELAYS),
            notice("Rerouted", DisruptionEffect.ROUTE_CHANGE),
        ).compactJourneyPlannerPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        // The headline is still real, first-occurrence text -- never replaced by a label -- even
        // though the effect itself is the generic fallback.
        assertEquals("Delayed", presentation?.headline)
    }

    @Test
    fun `a single deviation-sourced notice's own details body is carried through`() {
        val deviationNotice = JourneyDisruptionNotice(
            text = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
            effect = DisruptionEffect.NO_SERVICE,
            details = "På grund av ett tekniskt fel är trafiken inställd.",
        )
        val presentation = listOf(deviationNotice).compactJourneyPlannerPresentation()
        assertEquals(
            DisruptionPresentation(
                "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
                "På grund av ett tekniskt fel är trafiken inställd.",
                DisruptionEffect.NO_SERVICE,
            ),
            presentation,
        )
    }

    @Test
    fun `multiple genuinely different notices never surface a details body, even if the first one has one`() {
        val withDetails = JourneyDisruptionNotice("No service", DisruptionEffect.NO_SERVICE, details = "Full SL text here.")
        val other = notice("Rerouted", DisruptionEffect.ROUTE_CHANGE)
        val presentation = listOf(withDetails, other).compactJourneyPlannerPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        assertNull(presentation?.details)
    }

    // ---- compactPresentation (List<ResolvedJourneyDisruption> overload): the backend's own
    // fully-resolved, deduplicated, merged result -- the SECOND, deviations-aware post's own
    // aggregation. No combination/relevance inference happens here or anywhere else in Android;
    // this only ever collapses an already-resolved list into one compact presentation. ----

    @Test
    fun `empty resolved disruptions produce no presentation`() {
        assertNull(emptyList<ResolvedJourneyDisruption>().compactPresentation())
    }

    @Test
    fun `a single CONFIRMED entry is shown with its own real headline, details, and effect`() {
        val entry = resolved(
            id = "d1", headline = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
            details = "På grund av ett tekniskt fel är trafiken inställd.", effect = DisruptionEffect.NO_SERVICE,
            relevance = DisruptionRelevance.CONFIRMED,
        )
        val presentation = listOf(entry).compactPresentation()
        assertEquals(
            DisruptionPresentation(
                "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
                "På grund av ett tekniskt fel är trafiken inställd.",
                DisruptionEffect.NO_SERVICE,
            ),
            presentation,
        )
        assertTrue("CONFIRMED must never populate uncertainLineDesignations", presentation!!.uncertainLineDesignations.isEmpty())
    }

    @Test
    fun `a single LINE_RELEVANT entry still shows its own real headline and effect, plus the matched line designations`() {
        val entry = resolved(
            id = "d2", headline = "Trafiken är stängd mellan T-Centralen och Kungsträdgården",
            effect = DisruptionEffect.NO_SERVICE, relevance = DisruptionRelevance.LINE_RELEVANT,
            matchedLineDesignations = listOf("11"),
        )
        val presentation = listOf(entry).compactPresentation()
        // The real SL headline/effect are NOT hidden merely because relevance is line-level --
        // only the notification/widget's own compact indicator treats uncertainLineDesignations
        // specially (see that field's own doc); this function's job is just to carry it through.
        assertEquals("Trafiken är stängd mellan T-Centralen och Kungsträdgården", presentation?.headline)
        assertEquals(DisruptionEffect.NO_SERVICE, presentation?.effect)
        assertEquals(listOf("11"), presentation?.uncertainLineDesignations)
    }

    @Test
    fun `multiple distinct CONFIRMED entries fall back to the generic DISRUPTION effect with no line designations`() {
        val a = resolved(id = "d1", headline = "Delayed", effect = DisruptionEffect.DELAYS, relevance = DisruptionRelevance.CONFIRMED)
        val b = resolved(id = "d2", headline = "Rerouted", effect = DisruptionEffect.ROUTE_CHANGE, relevance = DisruptionRelevance.CONFIRMED)
        val presentation = listOf(a, b).compactPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        assertEquals("Delayed", presentation?.headline)
        assertNull(presentation?.details)
        assertTrue(presentation!!.uncertainLineDesignations.isEmpty())
    }

    @Test
    fun `multiple distinct LINE_RELEVANT entries fall back to the generic effect but keep the union of matched lines`() {
        val a = resolved(id = "d1", headline = "Delayed on 11", relevance = DisruptionRelevance.LINE_RELEVANT, matchedLineDesignations = listOf("11"))
        val b = resolved(id = "d2", headline = "Delayed on 17", relevance = DisruptionRelevance.LINE_RELEVANT, matchedLineDesignations = listOf("17"))
        val presentation = listOf(a, b).compactPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        // Conservative union, never dropped entirely -- the notification/widget builder itself
        // decides between naming a small number of lines or falling back to a fully generic
        // wording once there is more than one (see RoutineNotificationBuilder.lineRelevantDisruptionLabel).
        assertEquals(setOf("11", "17"), presentation?.uncertainLineDesignations?.toSet())
    }

    @Test
    fun `a mix of CONFIRMED and LINE_RELEVANT entries uses the plain generic label with no line designations`() {
        val confirmed = resolved(id = "d1", headline = "Delayed", relevance = DisruptionRelevance.CONFIRMED)
        val lineRelevant = resolved(id = "d2", headline = "Delayed on 11", relevance = DisruptionRelevance.LINE_RELEVANT, matchedLineDesignations = listOf("11"))
        val presentation = listOf(confirmed, lineRelevant).compactPresentation()
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
        // A mix must NOT silently claim line-only uncertainty when one of the two entries is
        // actually fully CONFIRMED -- nor may it claim full confidence either -- so it drops the
        // line-designation hint entirely rather than picking either extreme.
        assertTrue(presentation!!.uncertainLineDesignations.isEmpty())
    }

    @Test
    fun `two entries sharing the same id are deduplicated to one, even with differing text`() {
        val a = resolved(id = "shared", headline = "Delayed", effect = DisruptionEffect.DELAYS)
        val b = resolved(id = "shared", headline = "Rerouted", effect = DisruptionEffect.ROUTE_CHANGE)
        val presentation = listOf(a, b).compactPresentation()
        // distinctBy keeps the FIRST occurrence -- the single-entry path is taken since only one
        // distinct id survives, not the multi-entry generic fallback.
        assertEquals(DisruptionEffect.DELAYS, presentation?.effect)
        assertEquals("Delayed", presentation?.headline)
    }

    @Test
    fun `two Journey-Planner-sourced entries with null id are deduplicated by headline instead`() {
        val a = resolved(id = null, headline = "Rerouted via bus", effect = DisruptionEffect.ROUTE_CHANGE, source = DisruptionSource.JOURNEY_PLANNER)
        val b = resolved(id = null, headline = "Rerouted via bus", effect = DisruptionEffect.ROUTE_CHANGE, source = DisruptionSource.JOURNEY_PLANNER)
        val presentation = listOf(a, b).compactPresentation()
        assertEquals(DisruptionEffect.ROUTE_CHANGE, presentation?.effect)
        assertEquals("Rerouted via bus", presentation?.headline)
    }

    @Test
    fun `a Journey-Planner-sourced entry and a distinct SL-Deviations-sourced entry are both kept as genuinely different`() {
        val jp = resolved(id = null, headline = "Rerouted via replacement bus", effect = DisruptionEffect.REPLACEMENT_SERVICE, source = DisruptionSource.JOURNEY_PLANNER)
        val deviation = resolved(id = "dev1", headline = "Inställd trafik", effect = DisruptionEffect.NO_SERVICE, source = DisruptionSource.SL_DEVIATIONS)
        val presentation = listOf(jp, deviation).compactPresentation()
        // Two genuinely different distinct entries -- conservative generic fallback, never an
        // invented ranking between a Journey Planner notice and a matched deviation.
        assertEquals(DisruptionEffect.DISRUPTION, presentation?.effect)
    }
}
