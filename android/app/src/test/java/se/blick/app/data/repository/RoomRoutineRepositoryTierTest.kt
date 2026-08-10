package se.blick.app.data.repository

import android.app.Activity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.fail
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.local.room.RoutineDao
import se.blick.app.data.local.room.toEntity
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.RoutineType
import java.time.DayOfWeek
import java.time.LocalTime

class RoomRoutineRepositoryTierTest {
    private class Entitlement(initial: EntitlementState) : PremiumEntitlementRepository {
        private val state = MutableStateFlow(initial)
        override val entitlement: StateFlow<EntitlementState> = state
        override val localizedPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }
    private fun routine(id: String, start: Int = 7) = CommuteRoutine(
        id = id, name = id, siteId = 1, siteName = "Stop", transportMode = TransportMode.BUS,
        lineId = 1, lineDesignation = "1", directionCode = 1, destinationLabel = "End",
        activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(start, 0), endTime = LocalTime.of(start + 1, 0),
    )

    @Test fun `free save layer rejects a second routine`() = runTest {
        val dao = mockk<RoutineDao>(relaxed = true)
        val selection = mockk<FreeRoutineSelectionStore>()
        every { selection.selectedRoutineId } returns MutableStateFlow("one")
        coEvery { dao.getAll() } returns listOf(routine("one").toEntity())
        val repository = RoomRoutineRepository(dao, Entitlement(EntitlementState.Free), selection)
        try {
            repository.save(routine("two", 9))
            fail("Expected RoutineTierLimitException")
        } catch (_: RoutineTierLimitException) {
            // expected
        }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test fun `premium save layer accepts multiple non-overlapping routines`() = runTest {
        val dao = mockk<RoutineDao>(relaxed = true)
        val selection = mockk<FreeRoutineSelectionStore>(relaxed = true)
        every { selection.selectedRoutineId } returns MutableStateFlow("one")
        coEvery { dao.getAll() } returns listOf(routine("one").toEntity())
        val repository = RoomRoutineRepository(dao, Entitlement(EntitlementState.Premium), selection)
        repository.save(routine("two", 9))
        coVerify(exactly = 1) { dao.upsert(match { it.id == "two" }) }
    }

    @Test fun `revoked exact routines stay stored but do not block the one Free line routine`() = runTest {
        val dao = mockk<RoutineDao>(relaxed = true)
        val selection = mockk<FreeRoutineSelectionStore>(relaxed = true)
        every { selection.selectedRoutineId } returns MutableStateFlow(null)
        val lockedExact = routine("exact").copy(
            type = RoutineType.EXACT_DESTINATION,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin",
            journeyOriginName = "Stop",
            journeyDestinationId = "destination",
            journeyDestinationName = "Work",
        )
        coEvery { dao.getAll() } returns listOf(lockedExact.toEntity())
        val repository = RoomRoutineRepository(dao, Entitlement(EntitlementState.Free), selection)

        repository.save(routine("line", 9))

        coVerify(exactly = 1) { dao.upsert(match { it.id == "line" }) }
        verify(exactly = 1) { selection.select("line") }
    }
}
