package se.blick.app.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.notification.RoutineNotificationContent
import se.blick.app.notification.RoutineNotificationMapper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class ExactJourneyNotificationProjectionTest {
    @Test
    fun `notification contains only the fastest journey first leg`() {
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

        val projection = checkNotNull(plan.toFirstLegNotificationProjection(routine, now)) {
            "This journey has not departed yet -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals("14", model.lineLabel)
        assertEquals("Morsby centrum", model.directionLabel)
        assertEquals(firstLegDeparture, row.effectiveTime)
        assertEquals(5L, row.minutesRemaining)
        assertFalse(model.toString().contains("Arlanda airport"))
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

        assertNull(plan.toFirstLegNotificationProjection(routine, now))
    }

    @Test
    fun `a journey departing exactly at now still produces a zero-minute live notification`() {
        val departure = Instant.parse("2026-08-10T07:05:00Z")
        val now = departure
        val (routine, plan) = boundaryPlan(departure)

        val projection = checkNotNull(plan.toFirstLegNotificationProjection(routine, now)) {
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

        val projection = checkNotNull(plan.toFirstLegNotificationProjection(routine, now)) {
            "This journey has not departed yet -- a projection was expected"
        }
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals(1L, row.minutesRemaining)
    }
}
