package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.TripDeviation
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class LiveDeparturesProcessorTest {

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private val stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "BUSTERM")
    private val stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A")

    private fun line(
        id: Long = 705,
        designation: String = "705",
        transportMode: TransportMode = TransportMode.BUS,
    ) = LineRef(id = id, designation = designation, transportMode = transportMode)

    private fun departure(
        departureId: String = "dep-1",
        line: LineRef = line(),
        directionCode: Int? = 1,
        destination: String? = "Segeltorp",
        scheduledTime: Instant = now.plusSeconds(300),
        expectedTime: Instant? = null,
        isCancelled: Boolean = false,
        state: String = "EXPECTED",
        journeyState: String = "EXPECTED",
        predictionState: String? = null,
        tripDeviations: List<TripDeviation> = emptyList(),
    ) = Departure(
        departureId = departureId,
        line = line,
        direction = "Southbound",
        directionCode = directionCode,
        destination = destination,
        via = null,
        stopArea = stopArea,
        stopPoint = stopPoint,
        scheduledTime = scheduledTime,
        expectedTime = expectedTime,
        state = state,
        isCancelled = isCancelled,
        journey = Journey(id = 1, state = journeyState, predictionState = predictionState),
        tripDeviations = tripDeviations,
    )

    private fun routine(
        transportMode: TransportMode = TransportMode.BUS,
        lineId: Long? = null,
        directionCode: Int? = null,
    ) = CommuteRoutine(
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = transportMode,
        lineId = lineId,
        lineDesignation = null,
        directionCode = directionCode,
        destinationLabel = null,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun result(vararg departures: Departure) = DeparturesResult(
        fetchedAt = now,
        siteId = 9145,
        departures = departures.toList(),
        siteDeviations = emptyList(),
    )

    // ---- Transport mode ----

    @Test
    fun `matching transport mode is retained`() {
        val d = departure(line = line(transportMode = TransportMode.BUS))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(transportMode = TransportMode.BUS), now)
        assertEquals(1, prepared.size)
    }

    @Test
    fun `wrong transport mode is removed`() {
        val d = departure(line = line(transportMode = TransportMode.METRO))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(transportMode = TransportMode.BUS), now)
        assertTrue(prepared.isEmpty())
    }

    // ---- Line ----

    @Test
    fun `matching line is retained and wrong line is removed`() {
        val match = departure(departureId = "match", line = line(id = 705))
        val mismatch = departure(departureId = "mismatch", line = line(id = 999))
        val prepared = LiveDeparturesProcessor.prepare(result(match, mismatch), routine(lineId = 705), now)
        assertEquals(listOf("match"), prepared.map { it.departureId })
    }

    @Test
    fun `null routine lineId does not incorrectly remove departures`() {
        val a = departure(departureId = "a", line = line(id = 705))
        val b = departure(departureId = "b", line = line(id = 999))
        val prepared = LiveDeparturesProcessor.prepare(result(a, b), routine(lineId = null), now)
        assertEquals(setOf("a", "b"), prepared.map { it.departureId }.toSet())
    }

    // ---- Direction ----

    @Test
    fun `matching direction is retained and wrong direction is removed`() {
        val match = departure(departureId = "match", directionCode = 1)
        val mismatch = departure(departureId = "mismatch", directionCode = 2)
        val prepared = LiveDeparturesProcessor.prepare(result(match, mismatch), routine(directionCode = 1), now)
        assertEquals(listOf("match"), prepared.map { it.departureId })
    }

    @Test
    fun `null routine directionCode does not incorrectly remove departures`() {
        val a = departure(departureId = "a", directionCode = 1)
        val b = departure(departureId = "b", directionCode = 2)
        val prepared = LiveDeparturesProcessor.prepare(result(a, b), routine(directionCode = null), now)
        assertEquals(setOf("a", "b"), prepared.map { it.departureId }.toSet())
    }

    // ---- effectiveTime resolution ----

    @Test
    fun `expectedTime is preferred over scheduledTime`() {
        val scheduled = now.plusSeconds(600)
        val expected = now.plusSeconds(900)
        val d = departure(scheduledTime = scheduled, expectedTime = expected)
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals(expected, prepared.effectiveTime)
        assertEquals(scheduled, prepared.scheduledTime)
        assertEquals(expected, prepared.expectedTime)
    }

    @Test
    fun `scheduledTime is used when expectedTime is absent`() {
        val scheduled = now.plusSeconds(600)
        val d = departure(scheduledTime = scheduled, expectedTime = null)
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals(scheduled, prepared.effectiveTime)
    }

    // ---- Past-departure filtering ----

    @Test
    fun `departures before now are removed`() {
        val past = departure(scheduledTime = now.minusSeconds(60))
        val prepared = LiveDeparturesProcessor.prepare(result(past), routine(), now)
        assertTrue(prepared.isEmpty())
    }

    @Test
    fun `a departure exactly at now is retained with zero minutes remaining`() {
        val atNow = departure(scheduledTime = now)
        val prepared = LiveDeparturesProcessor.prepare(result(atNow), routine(), now).single()
        assertEquals(0L, prepared.minutesRemaining)
    }

    @Test
    fun `a scheduled-past but expected-future departure is retained`() {
        // The bus was scheduled to have already left, but real-time info says it's running
        // late and is actually still in the future — effectiveTime (expectedTime) must
        // govern the past/future filter, not scheduledTime.
        val d = departure(scheduledTime = now.minusSeconds(120), expectedTime = now.plusSeconds(180))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now)
        assertEquals(1, prepared.size)
        assertEquals(now.plusSeconds(180), prepared.single().effectiveTime)
    }

    // ---- Sorting and limiting ----

    @Test
    fun `results are sorted by effective time ascending before the limit is applied`() {
        // Deliberately out of chronological order in the input, and more than the limit,
        // so this only passes if sorting actually happens before take(): an unsorted
        // take(2) of (third, first, second) would wrongly yield [third, first].
        val third = departure(departureId = "third", scheduledTime = now.plusSeconds(900))
        val first = departure(departureId = "first", scheduledTime = now.plusSeconds(60))
        val second = departure(departureId = "second", scheduledTime = now.plusSeconds(300))
        val prepared = LiveDeparturesProcessor.prepare(result(third, first, second), routine(), now)
        assertEquals(listOf("first", "second"), prepared.map { it.departureId })
    }

    @Test
    fun `only the next two departures are returned`() {
        val departures = (1..5).map { i -> departure(departureId = "dep-$i", scheduledTime = now.plusSeconds(i * 60L)) }
        val prepared = LiveDeparturesProcessor.prepare(result(*departures.toTypedArray()), routine(), now)
        assertEquals(listOf("dep-1", "dep-2"), prepared.map { it.departureId })
    }

    @Test
    fun `the two earliest relevant departures are selected, filtering and sorting before the limit`() {
        // An earlier departure that doesn't match the routine's transport mode must not
        // occupy one of the two slots, and the two matching departures must still come
        // back in chronological order despite being supplied out of order.
        val earlierButWrongMode = departure(
            departureId = "wrong-mode",
            line = line(transportMode = TransportMode.METRO),
            scheduledTime = now.plusSeconds(10),
        )
        val third = departure(departureId = "third", scheduledTime = now.plusSeconds(900))
        val second = departure(departureId = "second", scheduledTime = now.plusSeconds(300))
        val first = departure(departureId = "first", scheduledTime = now.plusSeconds(60))

        val prepared = LiveDeparturesProcessor.prepare(
            result(earlierButWrongMode, third, second, first),
            routine(transportMode = TransportMode.BUS),
            now,
        )

        assertEquals(listOf("first", "second"), prepared.map { it.departureId })
    }

    // ---- Cancellation ----

    @Test
    fun `a future cancelled departure is preserved, not filtered out`() {
        val cancelled = departure(scheduledTime = now.plusSeconds(300), isCancelled = true)
        val prepared = LiveDeparturesProcessor.prepare(result(cancelled), routine(), now).single()
        assertTrue(prepared.isCancelled)
    }

    // ---- Real-time marker ----

    @Test
    fun `a departure with expectedTime is marked real-time`() {
        val d = departure(expectedTime = now.plusSeconds(300))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertTrue(prepared.isRealTime)
    }

    @Test
    fun `a departure without expectedTime is marked scheduled-only`() {
        val d = departure(expectedTime = null)
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertFalse(prepared.isRealTime)
    }

    // ---- Countdown minute-boundary behaviour ----

    @Test
    fun `a departure 30 seconds away rounds up to 1 minute`() {
        val d = departure(scheduledTime = now.plusSeconds(30))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals(1L, prepared.minutesRemaining)
    }

    @Test
    fun `a departure exactly 60 seconds away is 1 minute`() {
        val d = departure(scheduledTime = now.plusSeconds(60))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals(1L, prepared.minutesRemaining)
    }

    @Test
    fun `a departure 61 seconds away rounds up to 2 minutes`() {
        val d = departure(scheduledTime = now.plusSeconds(61))
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals(2L, prepared.minutesRemaining)
    }

    // ---- Existing state/deviation info is preserved ----

    @Test
    fun `departure state, journey state and trip deviations are all carried into the prepared departure`() {
        // Departure.state and Journey.state are distinct fields on the raw domain model
        // (see DepartureDto.kt) — use different values here to prove neither is dropped
        // nor conflated with the other during preparation.
        val deviation = TripDeviation(importanceLevel = 5, consequence = "DELAYED", message = "Running 5 min late")
        val d = departure(
            state = "DEVIATION",
            journeyState = "ATSTOP",
            predictionState = "REALTIME",
            tripDeviations = listOf(deviation),
        )
        val prepared = LiveDeparturesProcessor.prepare(result(d), routine(), now).single()
        assertEquals("DEVIATION", prepared.state)
        assertEquals("ATSTOP", prepared.journeyState)
        assertEquals("REALTIME", prepared.predictionState)
        assertEquals(listOf(deviation), prepared.tripDeviations)
    }
}
