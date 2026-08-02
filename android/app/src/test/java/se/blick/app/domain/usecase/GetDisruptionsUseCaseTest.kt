package se.blick.app.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.blick.app.data.repository.DisruptionRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class GetDisruptionsUseCaseTest {

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")

    private fun routine(
        lineId: Long? = 14,
        transportMode: TransportMode = TransportMode.METRO,
    ) = CommuteRoutine(
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = transportMode,
        lineId = lineId,
        lineDesignation = "14",
        directionCode = null,
        destinationLabel = null,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun disruption(id: String = "d1") = Disruption(
        disruptionId = id,
        version = 1,
        createdAt = now,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage("Header", "Details", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    private class FakeDisruptionRepository(private val result: List<Disruption>) : DisruptionRepository {
        var lastSiteId: Long? = null
        var lastLineId: Long? = null
        var lastTransportMode: TransportMode? = null
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            lastSiteId = siteId
            lastLineId = lineId
            lastTransportMode = transportMode
            callCount++
            return result
        }
    }

    private class FailingDisruptionRepository(private val error: Throwable) : DisruptionRepository {
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> = throw error
    }

    @Test
    fun `Loading is emitted before the terminal result`() = runTest {
        val useCase = GetDisruptionsUseCase(FakeDisruptionRepository(listOf(disruption())))
        val states = useCase(routine()).toList()

        assertEquals(2, states.size)
        assertEquals(DisruptionsState.Loading, states[0])
        assertTrue(states[1] is DisruptionsState.Loaded)
    }

    @Test
    fun `a non-empty result produces Loaded with the repository's own list, unchanged`() = runTest {
        val disruptions = listOf(disruption("a"), disruption("b"))
        val useCase = GetDisruptionsUseCase(FakeDisruptionRepository(disruptions))
        val loaded = useCase(routine()).toList().last() as DisruptionsState.Loaded

        assertEquals(disruptions, loaded.disruptions)
    }

    @Test
    fun `an empty result produces NoDisruptions, not a failure`() = runTest {
        val useCase = GetDisruptionsUseCase(FakeDisruptionRepository(emptyList()))
        val terminal = useCase(routine()).toList().last()

        assertEquals(DisruptionsState.NoDisruptions, terminal)
    }

    @Test
    fun `any repository failure produces Unavailable, never a raw exception`() = runTest {
        val useCase = GetDisruptionsUseCase(FailingDisruptionRepository(RuntimeException("boom")))
        val terminal = useCase(routine()).toList().last()

        assertEquals(DisruptionsState.Unavailable, terminal)
    }

    @Test
    fun `a connectivity-shaped failure also produces Unavailable -- no Offline-Unavailable split`() = runTest {
        val useCase = GetDisruptionsUseCase(FailingDisruptionRepository(java.io.IOException("unable to resolve host")))
        val terminal = useCase(routine()).toList().last()

        assertEquals(DisruptionsState.Unavailable, terminal)
    }

    @Test
    fun `coroutine cancellation is rethrown, not converted into Unavailable`() = runTest {
        val useCase = GetDisruptionsUseCase(FailingDisruptionRepository(CancellationException("test cancellation")))

        try {
            useCase(routine()).toList()
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `the repository is called with the routine's own siteId, lineId, and transportMode`() = runTest {
        val repository = FakeDisruptionRepository(emptyList())
        val theRoutine = routine(lineId = 42, transportMode = TransportMode.BUS)
        GetDisruptionsUseCase(repository)(theRoutine).toList()

        assertEquals(9145L, repository.lastSiteId)
        assertEquals(42L, repository.lastLineId)
        assertEquals(TransportMode.BUS, repository.lastTransportMode)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `TransportMode UNKNOWN is never sent as a query filter`() = runTest {
        val repository = FakeDisruptionRepository(emptyList())
        GetDisruptionsUseCase(repository)(routine(transportMode = TransportMode.UNKNOWN)).toList()

        assertEquals(null, repository.lastTransportMode)
    }

    @Test
    fun `a null lineId is passed through unchanged`() = runTest {
        val repository = FakeDisruptionRepository(emptyList())
        GetDisruptionsUseCase(repository)(routine(lineId = null)).toList()

        assertEquals(null, repository.lastLineId)
    }
}
