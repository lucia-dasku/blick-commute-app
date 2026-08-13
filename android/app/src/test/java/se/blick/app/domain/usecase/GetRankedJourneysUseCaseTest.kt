package se.blick.app.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.TransportMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class GetRankedJourneysUseCaseTest {
    private fun journey(
        id: String,
        departure: String,
        arrival: String,
        transfers: Int = 0,
        mode: TransportMode = TransportMode.BUS,
    ): JourneyPlan {
        val leg = JourneyLeg(mode, "1", "End", "A", "B", Instant.parse(departure), Instant.parse(arrival), true, emptyList())
        return JourneyPlan(id, "A", "B", Instant.parse(departure), Instant.parse(arrival), transfers, leg, listOf(leg), emptyList())
    }

    private fun repositoryOf(vararg journeys: JourneyPlan) = object : JourneyRepository {
        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
        ): List<JourneyPlan> = journeys.toList()
    }

    private fun fixedClock(instant: String) = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    /** A settable [Clock] whose [instant] a test (or a fake repository, simulating a network
     * round-trip's own real elapsed time) can advance mid-test — unlike [Clock.fixed], this can
     * prove WHEN [GetRankedJourneysUseCase] reads the clock relative to its own repository call,
     * not merely what it does with a single fixed value. */
    private class AdvancingClock(startInstant: Instant) : Clock() {
        var instant: Instant = startInstant
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zoneId: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    /** Simulates a [JourneyRepository.getJourneys] network round-trip that takes real time to
     * complete: advances [clock] to [advanceTo] as a side effect of being called, THEN returns
     * [journeys] — exactly modeling "the response only arrives after the clock has already moved
     * on". A test asserting on the result this produces can only pass if the code under test
     * reads `now` AFTER this call returns, not before it was ever made. */
    private class ClockAdvancingJourneyRepository(
        private val clock: AdvancingClock,
        private val advanceTo: Instant,
        private val journeys: List<JourneyPlan>,
    ) : JourneyRepository {
        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
        ): List<JourneyPlan> {
            clock.instant = advanceTo
            return journeys
        }
    }

    // ---- Clock read after the network response, not before the request (2026-08-10 22:12
    // production incident's remaining race: a request starting at 22:11:59.8 for a transport
    // departing at 22:12:00, whose response only arrives at 22:12:00.5) ----

    @Test fun `now is read only after the repository call returns, rejecting a journey that departed mid-request`() = runTest {
        val requestStart = Instant.parse("2026-08-10T22:11:59.800Z")
        val responseArrives = Instant.parse("2026-08-10T22:12:00.500Z")
        val clock = AdvancingClock(requestStart)
        // Departs AFTER requestStart but BEFORE responseArrives -- survives only if `now` is
        // (incorrectly) read before the repository call, at requestStart.
        val departedMidRequest = journey("departed-mid-request", "2026-08-10T22:12:00.000Z", "2026-08-10T22:20:00Z")
        val repository = ClockAdvancingJourneyRepository(clock, responseArrives, listOf(departedMidRequest))

        val result = GetRankedJourneysUseCase(repository, clock)("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(emptyList<JourneyPlan>(), result)
    }

    @Test fun `a journey still upcoming when the response arrives survives filtering`() = runTest {
        val requestStart = Instant.parse("2026-08-10T22:11:59.800Z")
        val responseArrives = Instant.parse("2026-08-10T22:12:00.500Z")
        val clock = AdvancingClock(requestStart)
        val stillUpcoming = journey("still-upcoming", "2026-08-10T22:15:00Z", "2026-08-10T22:25:00Z")
        val repository = ClockAdvancingJourneyRepository(clock, responseArrives, listOf(stillUpcoming))

        val result = GetRankedJourneysUseCase(repository, clock)("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(listOf("still-upcoming"), result.map { it.journeyId })
    }

    @Test fun `a journey departing exactly at the post-response now survives filtering`() = runTest {
        val requestStart = Instant.parse("2026-08-10T22:11:59.800Z")
        val responseArrives = Instant.parse("2026-08-10T22:12:00.500Z")
        val clock = AdvancingClock(requestStart)
        val exactlyOnTime = journey("exactly-on-time", "2026-08-10T22:12:00.500Z", "2026-08-10T22:20:00Z")
        val repository = ClockAdvancingJourneyRepository(clock, responseArrives, listOf(exactlyOnTime))

        val result = GetRankedJourneysUseCase(repository, clock)("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(listOf("exactly-on-time"), result.map { it.journeyId })
    }

    @Test fun `earliest final arrival wins even when it departs later`() {
        val earlyBusSlow = journey("slow", "2026-08-10T08:02:00Z", "2026-08-10T08:31:00Z")
        val laterMetroFast = journey("fast", "2026-08-10T08:04:00Z", "2026-08-10T08:23:00Z", mode = TransportMode.METRO)
        assertEquals(listOf("fast", "slow"), GetRankedJourneysUseCase.rank(listOf(earlyBusSlow, laterMetroFast)).map { it.journeyId })
    }

    @Test fun `a later trip using the same transport is not presented as an alternative`() {
        val first = journey("first", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z")
        val nextBus = journey("next", "2026-08-10T08:05:00Z", "2026-08-10T08:25:00Z")

        assertEquals(listOf("first"), GetRankedJourneysUseCase.rank(listOf(first, nextBus)).map { it.journeyId })
    }

    @Test fun `direct and transfer journeys retain full legs`() {
        val direct = journey("direct", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z")
        val transfer = journey("transfer", "2026-08-10T08:01:00Z", "2026-08-10T08:19:00Z", transfers = 1)
        val ranked = GetRankedJourneysUseCase.rank(listOf(direct, transfer))
        assertEquals(1, ranked.first().transferCount)
        assertEquals(1, ranked.first().legs.size)
    }

    @Test fun `selected transport modes are forwarded to journey planning`() = runTest {
        var receivedModes: Set<TransportMode>? = null
        val result = journey("train", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", mode = TransportMode.TRAIN)
        val repository = object : JourneyRepository {
            override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
            override suspend fun getJourneys(
                originId: String,
                destinationId: String,
                allowedTransportModes: Set<TransportMode>,
            ): List<JourneyPlan> {
                receivedModes = allowedTransportModes
                return listOf(result)
            }
        }

        GetRankedJourneysUseCase(repository, fixedClock("2026-08-10T07:00:00Z"))(
            "origin",
            "destination",
            setOf(TransportMode.TRAIN, TransportMode.BUS),
        )

        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), receivedModes)
    }

    // ---- Defensive filtering (2026-08-10 22:12 production incident: a bus that arrived at
    // 19:45 was shown as "fastest" and a metro that arrived at 22:10 as "alternative") ----

    @Test fun `journeys that already departed are removed before ranking, leaving none`() = runTest {
        val expiredBus = journey("bus", "2026-08-10T19:30:00Z", "2026-08-10T19:45:00Z", transfers = 3)
        val expiredMetro = journey("expired-metro", "2026-08-10T21:55:00Z", "2026-08-10T22:10:00Z", mode = TransportMode.METRO)
        val useCase = GetRankedJourneysUseCase(repositoryOf(expiredBus, expiredMetro), fixedClock("2026-08-10T22:12:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS, TransportMode.METRO))

        assertEquals(emptyList<JourneyPlan>(), result)
    }

    @Test fun `a genuinely upcoming journey survives the defensive filter and becomes fastest`() = runTest {
        val expired = journey("expired", "2026-08-10T19:30:00Z", "2026-08-10T19:45:00Z")
        // Earliest arrival of the two, but already arrived -- must never win fastest purely
        // because rank() would otherwise sort it first.
        val upcoming = journey("upcoming", "2026-08-10T22:15:00Z", "2026-08-10T22:35:00Z", mode = TransportMode.METRO)
        val useCase = GetRankedJourneysUseCase(repositoryOf(expired, upcoming), fixedClock("2026-08-10T22:12:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS, TransportMode.METRO))

        assertEquals(listOf("upcoming"), result.map { it.journeyId })
    }

    @Test fun `a journey departing exactly at now is not filtered out`() = runTest {
        val onTime = journey("on-time", "2026-08-10T22:12:00Z", "2026-08-10T22:30:00Z")
        val useCase = GetRankedJourneysUseCase(repositoryOf(onTime), fixedClock("2026-08-10T22:12:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(listOf("on-time"), result.map { it.journeyId })
    }

    @Test fun `a journey that departed one second ago is removed, not clamped to zero minutes`() = runTest {
        val justDeparted = journey("just-departed", "2026-08-10T22:11:59Z", "2026-08-10T22:30:00Z")
        val useCase = GetRankedJourneysUseCase(repositoryOf(justDeparted), fixedClock("2026-08-10T22:12:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(emptyList<JourneyPlan>(), result)
    }

    @Test fun `falls back to departureTime when firstLeg departureTime is null`() = runTest {
        val leg = JourneyLeg(TransportMode.BUS, "1", "End", "A", "B", null, null, isRealtime = false, disruptions = emptyList())
        val expired = JourneyPlan(
            "expired", "A", "B",
            departureTime = Instant.parse("2026-08-10T19:45:00Z"), arrivalTime = Instant.parse("2026-08-10T19:50:00Z"),
            transferCount = 0, firstLeg = leg, legs = listOf(leg), disruptions = emptyList(),
        )
        val useCase = GetRankedJourneysUseCase(repositoryOf(expired), fixedClock("2026-08-10T22:12:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS))

        assertEquals(emptyList<JourneyPlan>(), result)
    }

    // ---- Defensive max_changes=2 (mirrors backend/src/routes/journeys.ts's own MAX_CHANGES) ----

    @Test fun `a journey with more than two changes is removed before ranking`() = runTest {
        val threeChanges = journey("three-changes", "2026-08-10T08:00:00Z", "2026-08-10T08:10:00Z", transfers = 3)
        val twoChanges = journey("two-changes", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", transfers = 2, mode = TransportMode.METRO)
        val useCase = GetRankedJourneysUseCase(repositoryOf(threeChanges, twoChanges), fixedClock("2026-08-10T07:00:00Z"))

        val result = useCase("origin", "destination", setOf(TransportMode.BUS, TransportMode.METRO))

        assertEquals(listOf("two-changes"), result.map { it.journeyId })
    }

    @Test fun `zero, one and two changes are all allowed`() = runTest {
        for (changes in 0..2) {
            val candidate = journey("candidate", "2026-08-10T08:00:00Z", "2026-08-10T08:20:00Z", transfers = changes)
            val useCase = GetRankedJourneysUseCase(repositoryOf(candidate), fixedClock("2026-08-10T07:00:00Z"))

            val result = useCase("origin", "destination", setOf(TransportMode.BUS))

            assertEquals("changes=$changes", listOf("candidate"), result.map { it.journeyId })
        }
    }
}
