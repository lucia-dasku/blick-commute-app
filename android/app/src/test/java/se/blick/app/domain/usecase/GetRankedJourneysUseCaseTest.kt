package se.blick.app.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.TransportMode
import java.time.Instant

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

        GetRankedJourneysUseCase(repository)(
            "origin",
            "destination",
            setOf(TransportMode.TRAIN, TransportMode.BUS),
        )

        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), receivedModes)
    }
}
