package se.blick.app.data.repository

import android.app.Activity
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.local.room.OneTimeEventDao
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.scheduling.OneTimeEventScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RoomOneTimeEventRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC)
    private class Entitlement(initial: EntitlementState) : PremiumEntitlementRepository {
        override val entitlement: StateFlow<EntitlementState> = MutableStateFlow(initial)
        override val localizedPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }

    @Test fun `premium save persists and replaces scheduled reminder`() = runTest {
        val dao = mockk<OneTimeEventDao>(relaxed = true)
        val scheduler = mockk<OneTimeEventScheduler>(relaxed = true)
        val repository = RoomOneTimeEventRepository(dao, Entitlement(EntitlementState.Premium), scheduler, clock)
        val event = event()

        repository.save(event)

        coVerify(exactly = 1) { dao.upsert(match { it.id == event.id && it.timeType == "ARRIVE_BY" }) }
        verify(exactly = 1) { scheduler.schedule(event) }
    }

    @Test fun `free save is rejected without changing persisted data`() = runTest {
        val dao = mockk<OneTimeEventDao>(relaxed = true)
        val scheduler = mockk<OneTimeEventScheduler>(relaxed = true)
        val repository = RoomOneTimeEventRepository(dao, Entitlement(EntitlementState.Free), scheduler, clock)
        try {
            repository.save(event())
            fail("Expected Premium gate")
        } catch (_: OneTimeEventPremiumRequiredException) {
            // expected
        }
        coVerify(exactly = 0) { dao.upsert(any()) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test fun `delete cancels work and removes event`() = runTest {
        val dao = mockk<OneTimeEventDao>(relaxed = true)
        val scheduler = mockk<OneTimeEventScheduler>(relaxed = true)
        val repository = RoomOneTimeEventRepository(dao, Entitlement(EntitlementState.Premium), scheduler, clock)

        repository.delete("event-1")

        verify(exactly = 1) { scheduler.cancel("event-1") }
        coVerify(exactly = 1) { dao.deleteById("event-1") }
    }

    private fun event() = OneTimeEvent(
        id = "event-1",
        label = OneTimeEventLabel.TRAVEL,
        name = "Flight",
        originId = "A",
        originName = "Home",
        destinationId = "B",
        destinationName = "Arlanda",
        date = LocalDate.of(2026, 10, 2),
        time = LocalTime.of(7, 0),
        timeType = OneTimeEventTimeType.ARRIVE_BY,
    )
}
