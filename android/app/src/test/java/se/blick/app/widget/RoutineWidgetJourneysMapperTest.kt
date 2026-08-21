package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineLabel
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

    private fun routine(
        changesPreference: ExactDestinationChangesPreference = ExactDestinationChangesPreference.BOTH,
        label: RoutineLabel? = null,
    ) = CommuteRoutine(
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
        changesPreference = changesPreference,
        label = label,
    )

    @Test fun `the saved routine label is carried into a current journey widget model`() {
        val current = journey("current", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(label = RoutineLabel.STUDY), listOf(current), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(RoutineLabel.STUDY, model.label)
    }

    @Test fun `the saved routine label is carried into an empty journey widget model`() {
        val state = decideJourneysWidgetState(routine(label = RoutineLabel.HOME), emptyList(), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(RoutineLabel.HOME, model.label)
    }

    private fun journey(
        id: String,
        firstLegDeparture: Instant?,
        topLevelDeparture: Instant,
        lineDesignation: String? = "14",
        transfers: Int = 0,
        role: JourneyRole = JourneyRole.PRIMARY,
        legs: List<JourneyLeg>? = null,
    ): JourneyPlan {
        val leg = JourneyLeg(
            TransportMode.METRO, lineDesignation, "Direction", "Fruängen", "Arlanda",
            firstLegDeparture, topLevelDeparture.plusSeconds(600), true, emptyList(),
        )
        return JourneyPlan(
            id, "Fruängen", "Arlanda", topLevelDeparture, topLevelDeparture.plusSeconds(600),
            transfers, leg, legs ?: listOf(leg), emptyList(), role,
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

    // ---- changesPreference: copied from the routine's own persisted field onto the produced
    // Journeys content -- the single source of truth BlickRoutineWidget's own layout selection
    // reads (see RoutineWidgetContent.Journeys's own doc). ----

    @Test fun `changesPreference is copied from the routine onto the produced Journeys content`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(ExactDestinationChangesPreference.DIRECT_ONLY), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(ExactDestinationChangesPreference.DIRECT_ONLY, content.changesPreference)
    }

    @Test fun `a routine with the default BOTH changes preference produces Journeys content with BOTH`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(ExactDestinationChangesPreference.BOTH, content.changesPreference)
    }

    // ---- legBadges: one badge per public-transport leg, in order -- for BlickRoutineWidget's
    // own "relevant line badge(s)" row on a with-changes journey. ----

    @Test fun `legBadges carries one badge per leg, in order, for a multi-leg journey`() {
        val legs = listOf(
            JourneyLeg(TransportMode.METRO, "14", "Direction", "Fruängen", "Slussen", now.plusSeconds(60), now.plusSeconds(300), true, emptyList()),
            JourneyLeg(TransportMode.BUS, "40", "Direction", "Slussen", "Arlanda", now.plusSeconds(360), now.plusSeconds(660), true, emptyList()),
        )
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), transfers = 1, legs = legs)

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(
            listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
            content.primary.legBadges,
        )
    }

    @Test fun `a walking transfer leg (null lineDesignation) is excluded from legBadges`() {
        val legs = listOf(
            JourneyLeg(TransportMode.METRO, "14", "Direction", "Fruängen", "Slussen", now.plusSeconds(60), now.plusSeconds(300), true, emptyList()),
            // A walking leg: no transportation/line at all -- see normalizeJourney.ts's own
            // "WALK" doc.
            JourneyLeg(TransportMode.UNKNOWN, null, null, "Slussen", "Slussen", now.plusSeconds(300), now.plusSeconds(360), false, emptyList()),
            JourneyLeg(TransportMode.BUS, "40", "Direction", "Slussen", "Arlanda", now.plusSeconds(360), now.plusSeconds(660), true, emptyList()),
        )
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), transfers = 1, legs = legs)

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(
            listOf(WidgetJourneyLegBadge("14", TransportMode.METRO), WidgetJourneyLegBadge("40", TransportMode.BUS)),
            content.primary.legBadges,
        )
    }

    @Test fun `a direct single-leg journey produces exactly one legBadge`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60), lineDesignation = "14")

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(listOf(WidgetJourneyLegBadge("14", TransportMode.METRO)), content.primary.legBadges)
    }

    // ---- disruption: the current PRIMARY journey's own DisruptionPresentation, already derived
    // by the caller (RoutineActiveWindowWorker) from this same tick's journeys -- see
    // RoutineWidgetUpdater.updateWithJourneys's own doc. Only ever set on RoutineWidgetModel's
    // top-level disruptionHeadline, never re-derives anything from the journeys list itself. ----

    @Test fun `a supplied disruption sets the model's own disruptionHeadline to its real text`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation(
            "Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE,
        )

        val state = decideJourneysWidgetState(routine(), listOf(primary), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals("Hissen är ur funktion.", model.disruptionHeadline)
    }

    @Test fun `no disruption argument leaves disruptionHeadline null, matching the existing default`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertNull(model.disruptionHeadline)
    }

    @Test fun `a disruption never changes the Direct-Both-With-changes layout selection -- changesPreference is unaffected`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation(
            "Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE,
        )

        val state = decideJourneysWidgetState(
            routine(ExactDestinationChangesPreference.WITH_CHANGES_ONLY), listOf(primary), now, disruption = presentation,
        )

        val content = ((state as RoutineWidgetUiState.ActiveRoutine).model.content) as RoutineWidgetContent.Journeys
        assertEquals(ExactDestinationChangesPreference.WITH_CHANGES_ONLY, content.changesPreference)
    }

    @Test fun `an empty journeys list never attaches a disruption -- there is no PRIMARY to attach one to`() {
        val presentation = DisruptionPresentation(
            "Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE,
        )

        val state = decideJourneysWidgetState(routine(), emptyList(), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertNull(model.disruptionHeadline)
    }

    // ---- disruption.uncertainLineDesignations: mirrors disruptionHeadline's own handling one-
    // for-one -- see RoutineWidgetModel.disruptionUncertainLineDesignations' own doc. ----

    @Test fun `a LINE_RELEVANT disruption's uncertainLineDesignations is carried onto the model unchanged`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation(
            "Trafiken är stängd mellan T-Centralen och Kungsträdgården", null, DisruptionEffect.NO_SERVICE,
            uncertainLineDesignations = listOf("11"),
        )

        val state = decideJourneysWidgetState(routine(), listOf(primary), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(listOf("11"), model.disruptionUncertainLineDesignations)
    }

    @Test fun `a CONFIRMED disruption -- empty uncertainLineDesignations -- carries that through as empty too`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation("Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE)

        val state = decideJourneysWidgetState(routine(), listOf(primary), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertTrue(model.disruptionUncertainLineDesignations.isEmpty())
    }

    @Test fun `no disruption argument leaves disruptionUncertainLineDesignations empty, matching the existing default`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertTrue(model.disruptionUncertainLineDesignations.isEmpty())
    }

    @Test fun `an empty journeys list never attaches uncertainLineDesignations either -- there is no PRIMARY to attach one to`() {
        val presentation = DisruptionPresentation(
            "Trafiken är stängd mellan T-Centralen och Kungsträdgården", null, DisruptionEffect.NO_SERVICE,
            uncertainLineDesignations = listOf("11"),
        )

        val state = decideJourneysWidgetState(routine(), emptyList(), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertTrue(model.disruptionUncertainLineDesignations.isEmpty())
    }

    // ---- disruption.effect: mirrors disruptionHeadline's own handling one-for-one -- see
    // RoutineWidgetModel.disruptionEffect's own doc. ----

    @Test fun `a supplied disruption's effect is carried onto the model's own disruptionEffect`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation("Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE)

        val state = decideJourneysWidgetState(routine(), listOf(primary), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(DisruptionEffect.ACCESSIBILITY_ISSUE, model.disruptionEffect)
    }

    @Test fun `no disruption argument leaves disruptionEffect null, matching the existing default`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))

        val state = decideJourneysWidgetState(routine(), listOf(primary), now)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertNull(model.disruptionEffect)
    }

    @Test fun `a LINE_RELEVANT disruption's effect is still carried onto the model, alongside uncertainLineDesignations`() {
        val primary = journey("primary", now.plusSeconds(60), now.plusSeconds(60))
        val presentation = DisruptionPresentation(
            "Trafiken är stängd mellan T-Centralen och Kungsträdgården", null, DisruptionEffect.NO_SERVICE,
            uncertainLineDesignations = listOf("11"),
        )

        val state = decideJourneysWidgetState(routine(), listOf(primary), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertEquals(DisruptionEffect.NO_SERVICE, model.disruptionEffect)
        assertEquals(listOf("11"), model.disruptionUncertainLineDesignations)
    }

    @Test fun `an empty journeys list never attaches an effect either -- there is no PRIMARY to attach one to`() {
        val presentation = DisruptionPresentation("Hissen är ur funktion.", null, DisruptionEffect.ACCESSIBILITY_ISSUE)

        val state = decideJourneysWidgetState(routine(), emptyList(), now, disruption = presentation)

        val model = (state as RoutineWidgetUiState.ActiveRoutine).model
        assertNull(model.disruptionEffect)
    }
}
