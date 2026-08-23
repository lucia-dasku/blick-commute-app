package se.blick.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class ExactDestinationNotificationMapperTest {
    private val now = Instant.parse("2026-08-23T08:00:00Z")

    private val routine = CommuteRoutine(
        id = "exact-1",
        name = "Saved route",
        siteId = 1,
        siteName = "Legacy origin",
        transportMode = TransportMode.UNKNOWN,
        lineId = null,
        lineDesignation = "19",
        directionCode = null,
        destinationLabel = "Legacy destination",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        type = RoutineType.EXACT_DESTINATION,
        journeyOriginId = "A",
        journeyOriginName = "Slussen",
        journeyDestinationId = "B",
        journeyDestinationName = "Kungsträdgården",
    )

    private fun leg(
        mode: TransportMode,
        line: String?,
        direction: String?,
        departure: Instant,
        arrival: Instant,
    ) = JourneyLeg(
        transportMode = mode,
        lineDesignation = line,
        direction = direction,
        originName = "Origin",
        destinationName = "Destination",
        departureTime = departure,
        arrivalTime = arrival,
        isRealtime = true,
        disruptions = emptyList(),
    )

    private fun journey(
        id: String,
        role: JourneyRole,
        departureMinutes: Long,
        arrivalMinutes: Long,
        transferCount: Int,
        legs: List<JourneyLeg>,
    ) = JourneyPlan(
        journeyId = id,
        originName = "Slussen",
        destinationName = "Kungsträdgården",
        departureTime = now.plusSeconds(departureMinutes * 60),
        arrivalTime = now.plusSeconds(arrivalMinutes * 60),
        transferCount = transferCount,
        firstLeg = legs.first(),
        legs = legs,
        disruptions = emptyList(),
        role = role,
    )

    private fun liveState(primary: JourneyPlan): LiveDeparturesState {
        val departure = primary.firstLeg.departureTime!!
        return LiveDeparturesState.Live(
            LiveDeparturesSnapshot(
                listOf(
                    PreparedDeparture(
                        departureId = primary.journeyId,
                        lineDesignation = primary.firstLeg.lineDesignation.orEmpty(),
                        direction = primary.firstLeg.direction,
                        destination = primary.firstLeg.direction,
                        scheduledTime = departure,
                        expectedTime = departure,
                        effectiveTime = departure,
                        minutesRemaining = departure.epochSecond,
                        isRealTime = true,
                        isCancelled = false,
                        state = "EXPECTED",
                        journeyState = "NORMAL",
                        predictionState = null,
                        tripDeviations = emptyList(),
                        journeyRole = JourneyRole.PRIMARY,
                    ),
                ),
                now,
            ),
        )
    }

    @Test
    fun `saved exact route remains model identity while headsign stays in PRIMARY instructions`() {
        val first = leg(TransportMode.METRO, "19", "Hässelby strand", now.plusSeconds(240), now.plusSeconds(900))
        val primary = journey("primary", JourneyRole.PRIMARY, 4, 62, 0, listOf(first))

        val model = RoutineNotificationMapper.map(routine, liveState(primary), now, exactJourneys = listOf(primary))

        assertEquals(RoutineType.EXACT_DESTINATION, model.routineType)
        assertEquals("Slussen", model.stationName)
        assertEquals("Kungsträdgården", model.directionLabel)
        assertEquals("Hässelby strand", model.exactDestination!!.transitLegs.single().direction)
    }

    @Test
    fun `one-transfer PRIMARY carries ordered transit legs final arrival and PRIMARY change count while NEXT stays separate`() {
        val metro19 = leg(TransportMode.METRO, "19", "Hässelby strand", now.plusSeconds(240), now.plusSeconds(900))
        val metro11 = leg(TransportMode.METRO, "11", "Kungsträdgården", now.plusSeconds(960), now.plusSeconds(3720))
        val primary = journey("primary", JourneyRole.PRIMARY, 4, 62, 1, listOf(metro19, metro11))
        val alternative = journey("alternative", JourneyRole.ALTERNATIVE, 8, 50, 0, listOf(metro19.copy(departureTime = now.plusSeconds(480))))
        val next = journey("next", JourneyRole.NEXT, 12, 70, 1, listOf(metro19.copy(departureTime = now.plusSeconds(720))))

        val exact = RoutineNotificationMapper.map(
            routine,
            liveState(primary),
            now,
            exactJourneys = listOf(primary, alternative, next),
        ).exactDestination!!

        assertEquals(4L, exact.primaryCountdownMinutes)
        assertEquals(listOf("19", "11"), exact.transitLegs.map { it.lineDesignation })
        assertEquals(primary.arrivalTime, exact.arrivalTime)
        assertEquals(1, exact.primaryChangeCount)
        assertEquals(12L, exact.nextCountdownMinutes)
    }

    @Test
    fun `multi-transfer PRIMARY omits walking connectors and consecutive duplicate transit rides`() {
        val first = leg(TransportMode.METRO, "11", "Kungsträdgården", now.plusSeconds(180), now.plusSeconds(600))
        val walk = leg(TransportMode.UNKNOWN, null, null, now.plusSeconds(600), now.plusSeconds(660))
        val duplicate = first.copy(departureTime = now.plusSeconds(660), arrivalTime = now.plusSeconds(900))
        val second = leg(TransportMode.METRO, "17", "Skarpnäck", now.plusSeconds(960), now.plusSeconds(1500))
        val third = leg(TransportMode.BUS, "4", "Gullmarsplan", now.plusSeconds(1560), now.plusSeconds(2100))
        val primary = journey("primary", JourneyRole.PRIMARY, 3, 35, 2, listOf(first, walk, duplicate, second, third))

        val legs = RoutineNotificationMapper.map(
            routine,
            liveState(primary),
            now,
            exactJourneys = listOf(primary),
        ).exactDestination!!.transitLegs

        assertEquals(listOf("11", "17", "4"), legs.map { it.lineDesignation })
    }

    @Test
    fun `direct PRIMARY carries zero changes and cleanly omits absent NEXT`() {
        val first = leg(TransportMode.METRO, "13", "Ropsten", now.plusSeconds(180), now.plusSeconds(900))
        val primary = journey("primary", JourneyRole.PRIMARY, 3, 15, 0, listOf(first))

        val exact = RoutineNotificationMapper.map(
            routine,
            liveState(primary),
            now,
            exactJourneys = listOf(primary),
        ).exactDestination!!

        assertEquals(0, exact.primaryChangeCount)
        assertNull(exact.nextCountdownMinutes)
    }

    @Test
    fun `expired PRIMARY is not promoted from a still-current NEXT`() {
        val expiredLeg = leg(TransportMode.METRO, "19", "Hässelby strand", now.minusSeconds(1), now.plusSeconds(900))
        val expiredPrimary = journey("primary", JourneyRole.PRIMARY, -1, 15, 0, listOf(expiredLeg))
        val nextLeg = leg(TransportMode.METRO, "19", "Hässelby strand", now.plusSeconds(720), now.plusSeconds(1800))
        val next = journey("next", JourneyRole.NEXT, 12, 30, 0, listOf(nextLeg))

        val model = RoutineNotificationMapper.map(
            routine,
            liveState(next),
            now,
            exactJourneys = listOf(expiredPrimary, next),
        )

        assertNull(model.exactDestination)
        assertEquals(RoutineNotificationContent.Unavailable, model.content)
    }

    @Test
    fun `PRIMARY rollover rebuilds the presentation from the new backend PRIMARY`() {
        val oldLeg = leg(TransportMode.METRO, "19", "Hässelby strand", now.plusSeconds(30), now.plusSeconds(900))
        val oldPrimary = journey("old-primary", JourneyRole.PRIMARY, 1, 15, 0, listOf(oldLeg))
        val laterNow = now.plusSeconds(60)
        val newLeg = leg(TransportMode.BUS, "4", "Radiohuset", laterNow.plusSeconds(180), laterNow.plusSeconds(900))
        val newPrimary = JourneyPlan(
            journeyId = "new-primary",
            originName = "Slussen",
            destinationName = "Kungsträdgården",
            departureTime = laterNow.plusSeconds(180),
            arrivalTime = laterNow.plusSeconds(900),
            transferCount = 0,
            firstLeg = newLeg,
            legs = listOf(newLeg),
            disruptions = emptyList(),
            role = JourneyRole.PRIMARY,
        )

        val first = RoutineNotificationMapper.map(
            routine,
            liveState(oldPrimary),
            now,
            exactJourneys = listOf(oldPrimary),
        ).exactDestination!!
        val rolledOver = RoutineNotificationMapper.map(
            routine,
            liveState(newPrimary),
            laterNow,
            exactJourneys = listOf(newPrimary),
        ).exactDestination!!

        assertEquals("Hässelby strand", first.transitLegs.single().direction)
        assertEquals("Radiohuset", rolledOver.transitLegs.single().direction)
        assertEquals("4", rolledOver.transitLegs.single().lineDesignation)
        assertEquals(3L, rolledOver.primaryCountdownMinutes)
    }
}
