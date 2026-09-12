package se.blick.app.billing

import android.app.Activity
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.ads.isBannerEntitlementEligible
import se.blick.app.data.local.room.OneTimeEventDao
import se.blick.app.data.repository.RoomOneTimeEventRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.scheduling.OneTimeEventScheduler
import se.blick.app.ui.theme.shouldUseStockholmNightTheme

class ReviewerEntitlementCompositionTest {
    @Test
    fun `persisted reviewer grant is effective before Play startup refresh completes`() {
        val effective = effectivePremiumEntitlement(
            playEntitlement = EntitlementState.Loading,
            reviewerAccessActive = true,
            debugOverrideEnabled = false,
        )

        assertEquals(EntitlementState.Premium, effective)
        assertTrue(effective.hasPremiumAccess)
    }

    @Test
    fun `Play refresh restore callback and failure states cannot clear reviewer access`() {
        listOf(
            EntitlementState.Loading,
            EntitlementState.Free,
            EntitlementState.Pending,
            EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = false),
            EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = true),
            EntitlementState.Premium,
        ).forEach { playState ->
            assertEquals(
                EntitlementState.Premium,
                effectivePremiumEntitlement(
                    playEntitlement = playState,
                    reviewerAccessActive = true,
                    debugOverrideEnabled = false,
                ),
            )
        }
    }

    @Test
    fun `reviewer deactivation immediately reveals and preserves genuine purchase state`() {
        assertEquals(
            EntitlementState.Premium,
            effectivePremiumEntitlement(
                playEntitlement = EntitlementState.Premium,
                reviewerAccessActive = false,
                debugOverrideEnabled = false,
            ),
        )
        assertEquals(
            EntitlementState.Free,
            effectivePremiumEntitlement(
                playEntitlement = EntitlementState.Free,
                reviewerAccessActive = false,
                debugOverrideEnabled = false,
            ),
        )
    }

    @Test
    fun `reviewer entitlement feeds Premium and ad gates through the central state`() {
        val effective = effectivePremiumEntitlement(
            playEntitlement = EntitlementState.Free,
            reviewerAccessActive = true,
            debugOverrideEnabled = false,
        )

        assertTrue(effective.hasPremiumAccess)
        assertFalse(isBannerEntitlementEligible(effective))
        assertTrue(
            shouldUseStockholmNightTheme(
                requested = true,
                hasPremiumAccess = effective.hasPremiumAccess,
            ),
        )
    }

    @Test
    fun `reviewer entitlement unlocks multiple routines including exact destination`() {
        val line = lineRoutine("line", startHour = 7)
        val exact = lineRoutine("exact", startHour = 9).copy(
            type = RoutineType.EXACT_DESTINATION,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin",
            journeyOriginName = "Home",
            journeyDestinationId = "destination",
            journeyDestinationName = "Work",
        )
        val routines = listOf(line, exact)
        val reviewerEntitlement = reviewerEntitlement()

        assertTrue(RoutineTierPolicy.canRun(line, routines, reviewerEntitlement, selectedId = line.id))
        assertTrue(RoutineTierPolicy.canRun(exact, routines, reviewerEntitlement, selectedId = line.id))
    }

    @Test
    fun `reviewer entitlement passes the one-time event persistence gate`() = runTest {
        val dao = mockk<OneTimeEventDao>(relaxed = true)
        val scheduler = mockk<OneTimeEventScheduler>(relaxed = true)
        val event = OneTimeEvent(
            id = "review-event",
            label = OneTimeEventLabel.TRAVEL,
            name = "Flight",
            originId = "home",
            originName = "Home",
            destinationId = "airport",
            destinationName = "Airport",
            date = LocalDate.of(2026, 10, 2),
            time = LocalTime.of(7, 0),
            timeType = OneTimeEventTimeType.ARRIVE_BY,
        )
        val repository = RoomOneTimeEventRepository(
            dao = dao,
            entitlementRepository = FixedEntitlementRepository(reviewerEntitlement()),
            scheduler = scheduler,
            clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC),
        )

        repository.save(event)

        coVerify(exactly = 1) { dao.upsert(match { it.id == event.id }) }
        verify(exactly = 1) { scheduler.schedule(event) }
    }

    private fun reviewerEntitlement(): EntitlementState = effectivePremiumEntitlement(
        playEntitlement = EntitlementState.Free,
        reviewerAccessActive = true,
        debugOverrideEnabled = false,
    )

    private fun lineRoutine(id: String, startHour: Int) = CommuteRoutine(
        id = id,
        name = id,
        siteId = 1,
        siteName = "Stop",
        transportMode = TransportMode.BUS,
        lineId = 1,
        lineDesignation = "1",
        directionCode = 1,
        destinationLabel = "End",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(startHour, 0),
        endTime = LocalTime.of(startHour + 1, 0),
    )

    private class FixedEntitlementRepository(
        state: EntitlementState,
    ) : PremiumEntitlementRepository {
        override val entitlement: StateFlow<EntitlementState> = MutableStateFlow(state)
        override val localizedPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }
}
