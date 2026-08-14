package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Pure JVM tests for [decideJourneysWidgetState] — no Android/Glance dependency, no
 * [se.blick.app.data.repository.RoutineRepository] fake needed, mirroring
 * [RoutineWidgetReconcilerTest]'s own convention for testing a pure widget-state decision
 * directly. Exercises exactly what [GlanceRoutineWidgetUpdater.updateWithJourneys] delegates to.
 */
class RoutineWidgetJourneysMapperTest {

    private val now = Instant.parse("2026-08-10T22:12:00Z")

    private fun routine() = CommuteRoutine(
        id = "r1",
        name = "Airport commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.UNKNOWN,
        lineId = null,
        lineDesignation = null,
        directionCode = null,
        destinationLabel = null,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        type = RoutineType.EXACT_DESTINATION,
        journeyOriginId = "origin-id",
        journeyOriginName = "Fruängen",
        journeyDestinationId = "destination-id",
        journeyDestinationName = "Arlanda",
    )

    private fun journey(
        id: String,
        firstLegDeparture: Instant?,
        topLevelDeparture: Instant,
        lineDesignation: String? = "14",
        transfers: Int = 0,
        role: JourneyRole = JourneyRole.PRIMARY,
    ): JourneyPlan {
        val leg = JourneyLeg(
            TransportMode.METRO, lineDesignation, "Direction", "Fruängen", "Arlanda",
            firstLegDeparture, topLevelDeparture.plusSeconds(600), true, emptyList(),
        )
        return JourneyPlan(
            id, "Fruängen", "Arlanda", topLevelDeparture, topLevelDeparture.plusSeconds(600),
            transfers, leg, listOf(leg), emptyList(), role,
        )
    }

    @Test fun `an expired journey is never persisted as Journeys content -- falls back to NoUpcomingDepartures, not Unavailable`() {
        val expired = journey("expired", now.minusSeconds(1), now.minusSeconds(1))

        val state = decideJourneysWidgetState(routine(), listOf(expired), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        // The search itself succeeded (it returned a real journey) -- it has simply since
        // expired, which is not a failure. See the next two tests for the same distinction on
        // an empty list, and for fetchFailed = true actually producing Unavailable.
        assertEquals(RoutineWidgetContent.NoUpcomingDepartures(now), model.content)
    }

    @Test fun `an empty journey list produces NoUpcomingDepartures, not Unavailable, when the search itself did not fail`() {
        val state = decideJourneysWidgetState(routine(), emptyList(), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        // fetchFailed defaults to false: an empty list on its own means the search completed
        // successfully and genuinely found nothing (no eligible route right now, or none within
        // the configured change limit) -- not that anything is broken. Unavailable's own copy
        // ("Couldn't load departures right now. Will try again soon.") would wrongly claim a
        // retry is coming for a result that was already final.
        assertEquals(RoutineWidgetContent.NoUpcomingDepartures(now), model.content)
    }

    @Test fun `an empty journey list with fetchFailed = true produces Unavailable`() {
        val state = decideJourneysWidgetState(routine(), emptyList(), now, fetchFailed = true)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(RoutineWidgetContent.Unavailable, model.content)
    }

    @Test fun `a current journey is persisted with departureTime equal to its effective first-leg departure`() {
        val firstLegDeparture = Instant.parse("2026-08-10T22:15:00Z")
        val topLevelDeparture = Instant.parse("2026-08-10T22:10:00Z") // deliberately earlier/different
        val current = journey("current", firstLegDeparture, topLevelDeparture)

        val state = decideJourneysWidgetState(routine(), listOf(current), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(firstLegDeparture, content.primary.departureTime)
    }

    @Test fun `falls back to the top-level departureTime when firstLeg has none`() {
        val topLevelDeparture = Instant.parse("2026-08-10T22:15:00Z")
        val current = journey("current", null, topLevelDeparture)

        val state = decideJourneysWidgetState(routine(), listOf(current), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(topLevelDeparture, content.primary.departureTime)
    }

    @Test fun `an expired candidate never becomes the secondary row -- only a still-current second journey does`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val expiredCandidate = journey("expired-candidate", now.minusSeconds(1), now.minusSeconds(1), lineDesignation = "57", role = JourneyRole.NEXT)

        val state = decideJourneysWidgetState(routine(), listOf(primary, expiredCandidate), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertNull(content.secondary)
    }

    @Test fun `a genuinely current second journey is persisted alongside the primary`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val next = journey("next", now.plusSeconds(120), now.plusSeconds(120), lineDesignation = "57", role = JourneyRole.NEXT)

        val state = decideJourneysWidgetState(routine(), listOf(primary, next), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertTrue(content.secondary != null)
        assertEquals("57", content.secondary?.lineDesignation)
    }

    // ---- Backend-authoritative role: never inferred from list position -- see
    // WidgetJourneyRow.role's own doc. ----

    @Test fun `the primary row's role is populated from the journey's own backend-assigned role`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), role = JourneyRole.PRIMARY)

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(JourneyRole.PRIMARY, content.primary.role)
    }

    @Test fun `a NEXT-role second journey is persisted with role NEXT, not silently ALTERNATIVE`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), role = JourneyRole.PRIMARY)
        val next = journey("next", now.plusSeconds(120), now.plusSeconds(120), role = JourneyRole.NEXT)

        val state = decideJourneysWidgetState(routine(), listOf(primary, next), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(JourneyRole.NEXT, content.secondary?.role)
    }

    @Test fun `an ALTERNATIVE-role second journey is persisted with role ALTERNATIVE`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), role = JourneyRole.PRIMARY)
        val alternative = journey("alternative", now.plusSeconds(120), now.plusSeconds(90), role = JourneyRole.ALTERNATIVE)

        val state = decideJourneysWidgetState(routine(), listOf(primary, alternative), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(JourneyRole.ALTERNATIVE, content.secondary?.role)
    }

    // ---- PRIMARY/ALTERNATIVE/NEXT: the backend now sends up to three role-tagged journeys in
    // PRIMARY -> ALTERNATIVE? -> NEXT chronological order (see backend/src/routes/journeys.ts's
    // own doc) instead of the old two-entry fastest/alternative pair. The widget only ever wants
    // its own two most actionable rows -- taking the first two of that already-correctly-ordered
    // list is sufficient with no other change: PRIMARY+ALTERNATIVE during a large gap (the
    // genuinely useful third position, NEXT, stays available only in Routine Details), or
    // PRIMARY+NEXT normally, exactly like the two-entry tests above already prove. ----

    @Test fun `during a large gap, the widget shows PRIMARY and ALTERNATIVE, leaving the regular NEXT for Routine Details only`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), lineDesignation = "1", role = JourneyRole.PRIMARY)
        val alternative = journey("alternative", now.plusSeconds(120), now.plusSeconds(90), lineDesignation = "2", role = JourneyRole.ALTERNATIVE)
        val next = journey("next", now.plusSeconds(3600), now.plusSeconds(3660), lineDesignation = "1", role = JourneyRole.NEXT)

        val state = decideJourneysWidgetState(routine(), listOf(primary, alternative, next), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals("1", content.primary.lineDesignation)
        assertEquals("2", content.secondary?.lineDesignation)
        assertEquals(JourneyRole.ALTERNATIVE, content.secondary?.role)
    }
}
