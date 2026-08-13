package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
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
    ): JourneyPlan {
        val leg = JourneyLeg(
            TransportMode.METRO, lineDesignation, "Direction", "Fruängen", "Arlanda",
            firstLegDeparture, topLevelDeparture.plusSeconds(600), true, emptyList(),
        )
        return JourneyPlan(
            id, "Fruängen", "Arlanda", topLevelDeparture, topLevelDeparture.plusSeconds(600),
            transfers, leg, listOf(leg), emptyList(),
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
        assertEquals(firstLegDeparture, content.fastest.departureTime)
    }

    @Test fun `falls back to the top-level departureTime when firstLeg has none`() {
        val topLevelDeparture = Instant.parse("2026-08-10T22:15:00Z")
        val current = journey("current", null, topLevelDeparture)

        val state = decideJourneysWidgetState(routine(), listOf(current), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(topLevelDeparture, content.fastest.departureTime)
    }

    @Test fun `an expired candidate never becomes the alternative -- only a still-current second journey does`() {
        val fastest = journey("fastest", now.plusSeconds(60), now.plusSeconds(60))
        val expiredCandidate = journey("expired-candidate", now.minusSeconds(1), now.minusSeconds(1), lineDesignation = "57")

        val state = decideJourneysWidgetState(routine(), listOf(fastest, expiredCandidate), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertNull(content.alternative)
    }

    @Test fun `a genuinely current alternative is persisted alongside the fastest`() {
        val fastest = journey("fastest", now.plusSeconds(60), now.plusSeconds(60))
        val alternative = journey("alternative", now.plusSeconds(120), now.plusSeconds(120), lineDesignation = "57")

        val state = decideJourneysWidgetState(routine(), listOf(fastest, alternative), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertTrue(content.alternative != null)
        assertEquals("57", content.alternative?.lineDesignation)
    }
}
