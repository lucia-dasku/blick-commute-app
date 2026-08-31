package se.blick.app.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class GetLiveDeparturesUseCaseTest {

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "BUSTERM")
    private val stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A")

    private val routine = CommuteRoutine(
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.BUS,
        lineId = null,
        lineDesignation = null,
        directionCode = null,
        destinationLabel = null,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun upcomingDeparture(id: String = "dep-1") = Departure(
        departureId = id,
        line = LineRef(id = 705, designation = "705", transportMode = TransportMode.BUS),
        direction = "Southbound",
        directionCode = 1,
        destination = "Segeltorp",
        via = null,
        stopArea = stopArea,
        stopPoint = stopPoint,
        scheduledTime = now.plusSeconds(300),
        expectedTime = null,
        state = "EXPECTED",
        isCancelled = false,
        journey = Journey(id = 1, state = "EXPECTED", predictionState = null),
        tripDeviations = emptyList(),
    )

    private fun resultOf(vararg departures: Departure) = DeparturesResult(
        fetchedAt = now,
        siteId = 9145,
        departures = departures.toList(),
    )

    private class FakeDepartureRepository(private val result: DeparturesResult) : DepartureRepository {
        var lastSiteId: Long? = null
        var callCount = 0

        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            lastSiteId = siteId
            callCount++
            return result
        }
    }

    /** Throws whatever [error] the test supplies — models both connectivity and
     * non-connectivity failures, and a real coroutine cancellation, with a single fake. */
    private class FailingDepartureRepository(private val error: Throwable) : DepartureRepository {
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult = throw error
    }

    private fun useCase(repository: DepartureRepository) = GetLiveDeparturesUseCase(repository, clock)

    @Test
    fun `repository is called with the routine's saved site ID`() = runTest {
        val repository = FakeDepartureRepository(resultOf(upcomingDeparture()))
        useCase(repository).invoke(routine).toList()

        assertEquals(routine.siteId, repository.lastSiteId)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `Loading is emitted before the terminal result`() = runTest {
        val repository = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val states = useCase(repository).invoke(routine).toList()

        assertEquals(2, states.size)
        assertEquals(LiveDeparturesState.Loading, states[0])
        assertTrue(states[1] is LiveDeparturesState.Live)
    }

    @Test
    fun `a successful fetch with matching departures produces Live with prepared departures`() = runTest {
        val repository = FakeDepartureRepository(resultOf(upcomingDeparture("dep-1")))
        val states = useCase(repository).invoke(routine).toList()

        val live = states.last() as LiveDeparturesState.Live
        assertEquals(listOf("dep-1"), live.snapshot.departures.map { it.departureId })
        assertEquals(now, live.snapshot.fetchedAt)
    }

    @Test
    fun `the default use case contract still returns only two departures`() = runTest {
        val departures = (1..5).map { index ->
            upcomingDeparture("dep-$index").copy(scheduledTime = now.plusSeconds(index * 60L))
        }
        val repository = FakeDepartureRepository(resultOf(*departures.toTypedArray()))

        val live = useCase(repository).invoke(routine).toList().last() as LiveDeparturesState.Live

        assertEquals(listOf("dep-1", "dep-2"), live.snapshot.departures.map { it.departureId })
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `an explicit five departure limit retains five from one repository fetch`() = runTest {
        val departures = (1..6).map { index ->
            upcomingDeparture("dep-$index").copy(scheduledTime = now.plusSeconds(index * 60L))
        }
        val repository = FakeDepartureRepository(resultOf(*departures.reversed().toTypedArray()))

        val live = useCase(repository).invoke(
            routine = routine,
            maxDepartures = 5,
        ).toList().last() as LiveDeparturesState.Live

        assertEquals((1..5).map { "dep-$it" }, live.snapshot.departures.map { it.departureId })
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `an empty matching result produces NoUpcomingDepartures, not a failure`() = runTest {
        // A real response with departures, but none matching this routine's transport mode.
        val nonMatching = upcomingDeparture().copy(
            line = LineRef(id = 14, designation = "14", transportMode = TransportMode.METRO),
        )
        val repository = FakeDepartureRepository(resultOf(nonMatching))
        val states = useCase(repository).invoke(routine).toList()

        val terminal = states.last()
        assertTrue(terminal is LiveDeparturesState.NoUpcomingDepartures)
        assertEquals(now, (terminal as LiveDeparturesState.NoUpcomingDepartures).fetchedAt)
    }

    @Test
    fun `a connectivity failure with no previous data produces Offline`() = runTest {
        val repository = FailingDepartureRepository(IOException("unable to resolve host"))
        val states = useCase(repository).invoke(routine, previous = null).toList()

        assertEquals(LiveDeparturesState.Offline, states.last())
    }

    @Test
    fun `previous data plus a refresh failure produces Stale`() = runTest {
        val previous = LiveDeparturesSnapshot(
            departures = listOf(
                LiveDeparturesProcessor.prepare(resultOf(upcomingDeparture()), routine, now).single(),
            ),
            fetchedAt = now.minusSeconds(120),
        )
        val repository = FailingDepartureRepository(IOException("timeout"))
        val states = useCase(repository).invoke(routine, previous = previous).toList()

        val stale = states.last()
        assertTrue(stale is LiveDeparturesState.Stale)
        assertEquals(previous, (stale as LiveDeparturesState.Stale).snapshot)
    }

    @Test
    fun `a non-connectivity failure with previous data also produces Stale`() = runTest {
        val previous = LiveDeparturesSnapshot(departures = emptyList(), fetchedAt = now.minusSeconds(60))
        val repository = FailingDepartureRepository(RuntimeException("unexpected shape"))
        val states = useCase(repository).invoke(routine, previous = previous).toList()

        assertEquals(LiveDeparturesState.Stale(previous), states.last())
    }

    @Test
    fun `a non-connectivity failure with no previous data produces Unavailable`() = runTest {
        val repository = FailingDepartureRepository(RuntimeException("boom"))
        val states = useCase(repository).invoke(routine, previous = null).toList()

        assertEquals(LiveDeparturesState.Unavailable, states.last())
    }

    @Test
    fun `coroutine cancellation is rethrown, not converted into a failure state`() = runTest {
        val repository = FailingDepartureRepository(CancellationException("test cancellation"))

        try {
            useCase(repository).invoke(routine).toList()
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — never surfaced as Offline/Stale/Unavailable
        }
    }
}
