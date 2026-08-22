package se.blick.app.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.notification.RoutineNotificationContent
import se.blick.app.notification.RoutineNotificationMapper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class ExactJourneyNotificationProjectionTest {
    @Test
    fun `notification keeps the saved exact route while PRIMARY supplies live line and mode`() {
        val now = Instant.parse("2026-08-10T07:00:00Z")
        val firstLegDeparture = Instant.parse("2026-08-10T07:05:00Z")
        val finalArrival = Instant.parse("2026-08-10T08:10:00Z")
        val routine = CommuteRoutine(
            id = "exact-1",
            name = "Airport commute",
            siteId = 0,
            siteName = "Fruangen",
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            activeDays = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(9, 0),
            type = RoutineType.EXACT_DESTINATION,
            journeyOriginId = "origin",
            journeyOriginName = "Fruangen",
            journeyDestinationId = "destination",
            journeyDestinationName = "Arlanda airport",
        )
        val firstLeg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "14",
            direction = "Morsby centrum",
            originName = "Fruangen",
            destinationName = "T-Centralen",
            departureTime = firstLegDeparture,
            arrivalTime = Instant.parse("2026-08-10T07:25:00Z"),
            isRealtime = true,
            disruptions = emptyList(),
        )
        val plan = JourneyPlan(
            journeyId = "journey-1",
            originName = "Fruangen",
            destinationName = "Arlanda airport",
            departureTime = Instant.parse("2026-08-10T07:02:00Z"),
            arrivalTime = finalArrival,
            transferCount = 2,
            firstLeg = firstLeg,
            legs = listOf(firstLeg),
            disruptions = listOf("Later-leg disruption"),
        )

        val projection = checkNotNull(listOf(plan).toExactJourneyNotificationProjection(routine, now)) {
            "This journey has not departed yet -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals("Fruangen", model.stationName)
        assertEquals("Arlanda airport", model.directionLabel)
        assertEquals("14", model.lineLabel)
        assertEquals(TransportMode.METRO, projection.routine.transportMode)
        assertEquals(firstLegDeparture, row.effectiveTime)
        assertEquals(5L, row.minutesRemaining)
        assertFalse(model.directionLabel == "Morsby centrum")
        assertFalse(model.toString().contains(finalArrival.toString()))
        assertFalse(model.toString().contains("Later-leg disruption"))
    }

    /** Same routine/leg/plan shape as the boundary tests below, differing only in [departure]
     * and [now] — factored out since several tests below only ever vary that one relationship. */
    private fun boundaryPlan(departure: Instant): Pair<CommuteRoutine, JourneyPlan> {
        val routine = CommuteRoutine(
            id = "exact-boundary",
            name = "Boundary commute",
            siteId = 0,
            siteName = "Slussen",
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            activeDays = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(9, 0),
            type = RoutineType.EXACT_DESTINATION,
            journeyOriginId = "origin",
            journeyOriginName = "Slussen",
            journeyDestinationId = "destination",
            journeyDestinationName = "T-Centralen",
        )
        val firstLeg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "18",
            direction = "Hasselby strand",
            originName = "Slussen",
            destinationName = "T-Centralen",
            departureTime = departure,
            arrivalTime = departure.plusSeconds(180),
            isRealtime = true,
            disruptions = emptyList(),
        )
        val plan = JourneyPlan(
            journeyId = "journey-boundary",
            originName = "Slussen",
            destinationName = "T-Centralen",
            departureTime = departure,
            arrivalTime = departure.plusSeconds(180),
            transferCount = 0,
            firstLeg = firstLeg,
            legs = listOf(firstLeg),
            disruptions = emptyList(),
        )
        return routine to plan
    }

    /** Replaces a prior version of this test that asserted a journey which had already departed
     * 20 seconds earlier still produced a "0 min" live notification (by clamping its departure
     * to `now`). That behaviour was the root cause of a real production incident — a bus that
     * had already arrived kept being shown as "FASTEST" — and must no longer be protected: an
     * already-departed journey must be refused a live notification projection entirely, not
     * shown at zero minutes. */
    @Test
    fun `a journey that departed 20 seconds ago produces no live notification projection`() {
        val departure = Instant.parse("2026-08-10T07:05:00Z")
        val now = Instant.parse("2026-08-10T07:05:20Z")
        val (routine, plan) = boundaryPlan(departure)

        assertNull(listOf(plan).toExactJourneyNotificationProjection(routine, now))
    }

    @Test
    fun `a journey departing exactly at now still produces a zero-minute live notification`() {
        val departure = Instant.parse("2026-08-10T07:05:00Z")
        val now = departure
        val (routine, plan) = boundaryPlan(departure)

        val projection = checkNotNull(listOf(plan).toExactJourneyNotificationProjection(routine, now)) {
            "A departure exactly at now must still produce a projection"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals(now, row.effectiveTime)
        assertEquals(0L, row.minutesRemaining)
    }

    @Test
    fun `a journey departing 25 seconds from now displays one minute, not zero`() {
        val now = Instant.parse("2026-08-10T07:05:00Z")
        val departure = now.plusSeconds(25)
        val (routine, plan) = boundaryPlan(departure)

        val projection = checkNotNull(listOf(plan).toExactJourneyNotificationProjection(routine, now)) {
            "This journey has not departed yet -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals(1L, row.minutesRemaining)
    }

    // ---- Two-row projection: the notification infrastructure supports two departure rows, and
    // an exact-destination routine must now actually use both -- see this file's own doc. ----

    @Test
    fun `exposes two departures when two journeys are current -- not just the first`() {
        val now = Instant.parse("2026-08-10T07:00:00Z")
        val (routine, primaryPlan) = boundaryPlan(Instant.parse("2026-08-10T07:05:00Z"))
        val (_, nextPlan) = boundaryPlan(Instant.parse("2026-08-10T07:20:00Z"))
        val secondPlan = nextPlan.copy(journeyId = "journey-next")

        val projection = checkNotNull(listOf(primaryPlan, secondPlan).toExactJourneyNotificationProjection(routine, now)) {
            "Two current journeys were supplied -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val rows = (model.content as RoutineNotificationContent.Live).departures

        assertEquals(2, rows.size)
        assertEquals(5L, rows[0].minutesRemaining)
        assertEquals(20L, rows[1].minutesRemaining)
    }

    @Test
    fun `a third journey is never projected -- only the first two current ones are`() {
        val now = Instant.parse("2026-08-10T07:00:00Z")
        val (routine, first) = boundaryPlan(Instant.parse("2026-08-10T07:05:00Z"))
        val second = boundaryPlan(Instant.parse("2026-08-10T07:20:00Z")).second.copy(journeyId = "journey-second")
        val third = boundaryPlan(Instant.parse("2026-08-10T07:40:00Z")).second.copy(journeyId = "journey-third")

        val projection = checkNotNull(listOf(first, second, third).toExactJourneyNotificationProjection(routine, now))
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val rows = (model.content as RoutineNotificationContent.Live).departures

        assertEquals(2, rows.size)
        assertEquals(listOf(5L, 20L), rows.map { it.minutesRemaining })
    }

    @Test
    fun `an expired first journey is skipped so the still-current second one is projected alone`() {
        val now = Instant.parse("2026-08-10T07:05:20Z")
        val (routine, expired) = boundaryPlan(Instant.parse("2026-08-10T07:05:00Z"))
        val current = boundaryPlan(Instant.parse("2026-08-10T07:20:00Z")).second.copy(journeyId = "journey-current")

        val projection = checkNotNull(listOf(expired, current).toExactJourneyNotificationProjection(routine, now)) {
            "The second journey is still current -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val rows = (model.content as RoutineNotificationContent.Live).departures

        assertEquals(1, rows.size)
        assertEquals(15L, rows[0].minutesRemaining)
    }

    // ---- journeyRole flows end-to-end: JourneyPlan.role -> toPreparedDeparture ->
    // RoutineNotificationMapper -> NotificationDepartureRow.journeyRole -- see
    // toExactJourneyNotificationProjection's own doc on why selection stays positional while
    // each row's own role is still carried through unchanged. ----

    @Test
    fun `a PRIMARY plus NEXT pair carries each journey's own real role through to its notification row`() {
        val now = Instant.parse("2026-08-10T07:00:00Z")
        val (routine, primaryPlan) = boundaryPlan(Instant.parse("2026-08-10T07:05:00Z"))
        val (_, nextPlan) = boundaryPlan(Instant.parse("2026-08-10T07:20:00Z"))
        val second = nextPlan.copy(journeyId = "journey-next", role = JourneyRole.NEXT)

        val projection = checkNotNull(listOf(primaryPlan, second).toExactJourneyNotificationProjection(routine, now))
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val rows = (model.content as RoutineNotificationContent.Live).departures

        assertEquals(JourneyRole.PRIMARY, rows[0].journeyRole)
        assertEquals(JourneyRole.NEXT, rows[1].journeyRole)
    }

    @Test
    fun `a PRIMARY plus ALTERNATIVE pair carries ALTERNATIVE through to the second notification row`() {
        val now = Instant.parse("2026-08-10T07:00:00Z")
        val (routine, primaryPlan) = boundaryPlan(Instant.parse("2026-08-10T07:05:00Z"))
        val (_, altPlan) = boundaryPlan(Instant.parse("2026-08-10T07:20:00Z"))
        val second = altPlan.copy(journeyId = "journey-alt", role = JourneyRole.ALTERNATIVE)

        val projection = checkNotNull(listOf(primaryPlan, second).toExactJourneyNotificationProjection(routine, now))
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val rows = (model.content as RoutineNotificationContent.Live).departures

        assertEquals(JourneyRole.PRIMARY, rows[0].journeyRole)
        assertEquals(JourneyRole.ALTERNATIVE, rows[1].journeyRole)
    }
}
