package se.blick.app.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val projection = plan.toFirstLegNotificationProjection(routine, now)
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

    @Test
    fun `journey that departed seconds ago remains a zero-minute live notification`() {
        val departure = Instant.parse("2026-08-10T07:05:00Z")
        val now = Instant.parse("2026-08-10T07:05:20Z")
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
            arrivalTime = Instant.parse("2026-08-10T07:08:00Z"),
            isRealtime = true,
            disruptions = emptyList(),
        )
        val plan = JourneyPlan(
            journeyId = "journey-boundary",
            originName = "Slussen",
            destinationName = "T-Centralen",
            departureTime = departure,
            arrivalTime = Instant.parse("2026-08-10T07:08:00Z"),
            transferCount = 0,
            firstLeg = firstLeg,
            legs = listOf(firstLeg),
            disruptions = emptyList(),
        )

        val projection = plan.toFirstLegNotificationProjection(routine, now)
        val model = RoutineNotificationMapper.map(projection.routine, projection.departuresState, now)
        val row = (model.content as RoutineNotificationContent.Live).departures.single()

        assertEquals(now, row.effectiveTime)
        assertEquals(0L, row.minutesRemaining)
    }
}
