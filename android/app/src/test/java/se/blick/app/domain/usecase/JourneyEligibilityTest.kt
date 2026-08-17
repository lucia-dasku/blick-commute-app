package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

/**
 * Pure JVM tests for [isDepartureCurrent], [JourneyPlan.effectiveFirstDeparture],
 * [JourneyPlan.isCurrentJourney] and [List<JourneyPlan>.filterCurrentJourneys] — the one shared
 * eligibility definition every exact-journey consumer ([GetRankedJourneysUseCase],
 * [se.blick.app.scheduling.RoutineActiveWindowWorker], [se.blick.app.widget.GlanceRoutineWidgetUpdater],
 * [se.blick.app.widget.BlickRoutineWidget] and
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsContent]) is built from.
 */
class JourneyEligibilityTest {

    private val now = Instant.parse("2026-08-10T22:12:00Z")

    private fun journey(
        id: String,
        firstLegDeparture: Instant?,
        topLevelDeparture: Instant,
        transfers: Int = 0,
    ): JourneyPlan {
        val leg = JourneyLeg(
            TransportMode.BUS, "1", "End", "A", "B",
            firstLegDeparture, topLevelDeparture.plusSeconds(600), true, emptyList(),
        )
        return JourneyPlan(id, "A", "B", topLevelDeparture, topLevelDeparture.plusSeconds(600), transfers, leg, listOf(leg), emptyList())
    }

    // ---- isDepartureCurrent: the >= boundary ----

    @Test fun `a departure exactly at now is current`() {
        assertTrue(isDepartureCurrent(now, now))
    }

    @Test fun `a departure one millisecond before now is not current`() {
        assertFalse(isDepartureCurrent(now, now.minusMillis(1)))
    }

    @Test fun `a departure in the future is current`() {
        assertTrue(isDepartureCurrent(now, now.plusSeconds(30)))
    }

    // ---- effectiveFirstDeparture: firstLeg wins, top-level departureTime is the fallback ----

    @Test fun `effectiveFirstDeparture uses firstLeg departureTime when present`() {
        val firstLeg = Instant.parse("2026-08-10T22:15:00Z")
        val topLevel = Instant.parse("2026-08-10T22:10:00Z") // deliberately different/earlier
        val plan = journey("j1", firstLeg, topLevel)

        assertEquals(firstLeg, plan.effectiveFirstDeparture())
    }

    @Test fun `effectiveFirstDeparture falls back to the top-level departureTime when firstLeg has none`() {
        val topLevel = Instant.parse("2026-08-10T22:10:00Z")
        val plan = journey("j1", null, topLevel)

        assertEquals(topLevel, plan.effectiveFirstDeparture())
    }

    // ---- PRIMARY expiry boundary: the first millisecond satisfying departure < now ----

    @Test fun `primary expiry boundary is one millisecond after its effective first departure`() {
        val effectiveDeparture = now.plusSeconds(10)
        val primary = journey("primary", effectiveDeparture, now.minusSeconds(30))

        val boundary = listOf(primary).primaryJourneyExpiryBoundary()!!

        assertEquals("primary", boundary.journeyId)
        assertEquals(effectiveDeparture, boundary.effectiveDeparture)
        assertEquals(effectiveDeparture.plusMillis(1), boundary.firstExpiredAt)
        assertEquals(10_001L, boundary.remainingMillis(now))
        assertEquals(0L, boundary.remainingMillis(effectiveDeparture.plusMillis(2)))
    }

    @Test fun `primary expiry boundary never promotes NEXT`() {
        val next = journey("next", now.plusSeconds(10), now.plusSeconds(10)).copy(role = JourneyRole.NEXT)

        assertEquals(null, listOf(next).primaryJourneyExpiryBoundary())
    }

    // ---- isCurrentJourney: departure eligibility AND the two-change limit together ----

    @Test fun `a journey departing exactly at now with two changes is current`() {
        val plan = journey("j1", now, now, transfers = 2)
        assertTrue(plan.isCurrentJourney(now))
    }

    @Test fun `a journey that departed one millisecond ago is not current, even with zero changes`() {
        val plan = journey("j1", now.minusMillis(1), now.minusMillis(1), transfers = 0)
        assertFalse(plan.isCurrentJourney(now))
    }

    @Test fun `an upcoming journey with three changes is not current`() {
        val plan = journey("j1", now.plusSeconds(60), now.plusSeconds(60), transfers = 3)
        assertFalse(plan.isCurrentJourney(now))
    }

    @Test fun `isCurrentJourney uses the effective first departure, not the top-level one`() {
        // Top-level departureTime is already in the past, but the effective first-leg departure
        // (what a rider would actually board) is still upcoming -- must be current.
        val plan = journey("j1", firstLegDeparture = now.plusSeconds(30), topLevelDeparture = now.minusSeconds(30))
        assertTrue(plan.isCurrentJourney(now))
    }

    // ---- filterCurrentJourneys: list-level convenience, preserves order ----

    @Test fun `filterCurrentJourneys removes expired and over-the-limit journeys, preserving order`() {
        val expired = journey("expired", now.minusSeconds(1), now.minusSeconds(1))
        val tooManyChanges = journey("too-many-changes", now.plusSeconds(10), now.plusSeconds(10), transfers = 3)
        val valid1 = journey("valid-1", now.plusSeconds(20), now.plusSeconds(20))
        val valid2 = journey("valid-2", now, now)

        val result = listOf(expired, tooManyChanges, valid1, valid2).filterCurrentJourneys(now)

        assertEquals(listOf("valid-1", "valid-2"), result.map { it.journeyId })
    }

    @Test fun `filterCurrentJourneys on an all-expired list returns empty`() {
        val expired1 = journey("expired-1", now.minusSeconds(60), now.minusSeconds(60))
        val expired2 = journey("expired-2", now.minusSeconds(1), now.minusSeconds(1))

        val result = listOf(expired1, expired2).filterCurrentJourneys(now)

        assertEquals(emptyList<JourneyPlan>(), result)
    }
}
