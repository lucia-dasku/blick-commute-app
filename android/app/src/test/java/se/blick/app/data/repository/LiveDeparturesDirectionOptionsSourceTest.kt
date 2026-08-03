package se.blick.app.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import java.time.Instant
import java.util.UUID

/**
 * Plain JVM tests for [LiveDeparturesDirectionOptionsSource] — previously untested (a thin
 * wrapper), now covers the one real behavior change: requesting
 * [DIRECTION_DISCOVERY_FORECAST_MINUTES] specifically for setup-time direction discovery,
 * distinct from every other [DepartureRepository.getDepartures] caller (the live
 * routine-details/notification polling paths), which never pass a forecast value at all.
 */
class LiveDeparturesDirectionOptionsSourceTest {

    private fun departure(
        lineId: Long = 14,
        designation: String = "14",
        directionCode: Int? = 1,
        destination: String? = "T-Centralen",
        mode: TransportMode = TransportMode.METRO,
    ) = Departure(
        departureId = UUID.randomUUID().toString(),
        line = LineRef(id = lineId, designation = designation, transportMode = mode),
        direction = destination,
        directionCode = directionCode,
        destination = destination,
        via = null,
        stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "METROSTN"),
        stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A"),
        scheduledTime = Instant.parse("2026-07-27T05:05:00Z"),
        expectedTime = null,
        state = "EXPECTED",
        isCancelled = false,
        journey = Journey(id = 1, state = "EXPECTED", predictionState = null),
        tripDeviations = emptyList(),
    )

    private class RecordingDepartureRepository(private val result: DeparturesResult) : DepartureRepository {
        var lastForecastMinutes: Int? = -1
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            lastForecastMinutes = forecastMinutes
            return result
        }
    }

    @Test
    fun `requests the maximum SL Transport forecast window, not the live-display default`() = runTest {
        val repository = RecordingDepartureRepository(DeparturesResult(Instant.EPOCH, 9145, emptyList()))

        LiveDeparturesDirectionOptionsSource(repository).getDirectionOptions(9145)

        assertEquals(DIRECTION_DISCOVERY_FORECAST_MINUTES, repository.lastForecastMinutes)
        assertEquals(1200, repository.lastForecastMinutes)
    }

    @Test
    fun `maps departures to direction options`() = runTest {
        val result = DeparturesResult(Instant.EPOCH, 9145, listOf(departure()))
        val repository = RecordingDepartureRepository(result)

        val options = LiveDeparturesDirectionOptionsSource(repository).getDirectionOptions(9145)

        assertEquals(
            listOf(DirectionOption(14, "14", TransportMode.METRO, 1, "T-Centralen")),
            options,
        )
    }

    @Test
    fun `deduplicates departures sharing the same line, direction, and mode`() = runTest {
        val result = DeparturesResult(
            Instant.EPOCH,
            9145,
            listOf(departure(), departure(), departure()),
        )
        val repository = RecordingDepartureRepository(result)

        val options = LiveDeparturesDirectionOptionsSource(repository).getDirectionOptions(9145)

        assertEquals(1, options.size)
    }

    @Test
    fun `keeps distinct lines and directions as separate options`() = runTest {
        val result = DeparturesResult(
            Instant.EPOCH,
            9145,
            listOf(departure(lineId = 14, directionCode = 1), departure(lineId = 14, directionCode = 2), departure(lineId = 17, directionCode = 1)),
        )
        val repository = RecordingDepartureRepository(result)

        val options = LiveDeparturesDirectionOptionsSource(repository).getDirectionOptions(9145)

        assertEquals(3, options.size)
    }
}
