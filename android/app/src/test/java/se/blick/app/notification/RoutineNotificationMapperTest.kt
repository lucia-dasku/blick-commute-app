package se.blick.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Pure JVM tests for [RoutineNotificationMapper] — no Android dependency, no Robolectric.
 * The real, constructed [android.app.Notification] is covered separately by
 * `RoutineNotificationBuilderTest`.
 */
class RoutineNotificationMapperTest {

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private fun routine(
        id: String = "r1",
        siteName: String = "Fruängen",
        lineDesignation: String? = "14",
        destinationLabel: String? = "Fruängen",
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = siteName,
        transportMode = TransportMode.METRO,
        lineId = null,
        lineDesignation = lineDesignation,
        directionCode = null,
        destinationLabel = destinationLabel,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun prepared(
        departureId: String = "dep-1",
        lineDesignation: String = "14",
        direction: String? = "Southbound",
        destination: String? = "Fruängen",
        effectiveTime: Instant = now.plusSeconds(300),
        minutesRemaining: Long = 999L, // deliberately wrong/stale -- the mapper must recompute, never trust this
        isRealTime: Boolean = true,
        isCancelled: Boolean = false,
    ) = PreparedDeparture(
        departureId = departureId,
        lineDesignation = lineDesignation,
        direction = direction,
        destination = destination,
        scheduledTime = effectiveTime,
        expectedTime = if (isRealTime) effectiveTime else null,
        effectiveTime = effectiveTime,
        minutesRemaining = minutesRemaining,
        isRealTime = isRealTime,
        isCancelled = isCancelled,
        state = "EXPECTED",
        journeyState = "EXPECTED",
        predictionState = null,
        tripDeviations = emptyList(),
    )

    private fun snapshot(vararg departures: PreparedDeparture, fetchedAt: Instant = now) =
        LiveDeparturesSnapshot(departures.toList(), fetchedAt)

    private fun disruption(header: String = "Delays on line 14", details: String = "Expect longer travel times.") = Disruption(
        disruptionId = "d1",
        version = 1,
        createdAt = now,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage(header, details, null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    // ---- Routine identity fields ----

    @Test
    fun `routine id, station name are carried into the model`() {
        val model = RoutineNotificationMapper.map(routine(id = "r42", siteName = "Slussen"), LiveDeparturesState.Loading, now)
        assertEquals("r42", model.routineId)
        assertEquals("Slussen", model.stationName)
    }

    @Test
    fun `pinned line and direction are carried into the model`() {
        val model = RoutineNotificationMapper.map(
            routine(lineDesignation = "14", destinationLabel = "Fruängen"),
            LiveDeparturesState.Loading,
            now,
        )
        assertEquals("14", model.lineLabel)
        assertEquals("Fruängen", model.directionLabel)
    }

    @Test
    fun `missing pinned line and direction fall back to null, not empty strings`() {
        val model = RoutineNotificationMapper.map(
            routine(lineDesignation = null, destinationLabel = null),
            LiveDeparturesState.Loading,
            now,
        )
        assertNull(model.lineLabel)
        assertNull(model.directionLabel)
    }

    // ---- Loading ----

    @Test
    fun `Loading maps to Loading content`() {
        val model = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Loading, now)
        assertEquals(RoutineNotificationContent.Loading, model.content)
    }

    // ---- Live ----

    @Test
    fun `Live state maps to Live content with its departures`() {
        val state = LiveDeparturesState.Live(snapshot(prepared(departureId = "a"), prepared(departureId = "b", effectiveTime = now.plusSeconds(600))))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(listOf("a" to now.plusSeconds(300), "b" to now.plusSeconds(600)).map { it.second }, content.departures.map { it.effectiveTime })
    }

    @Test
    fun `Live is capped at a maximum of two departures`() {
        val departures = (1..5).map { i -> prepared(departureId = "dep-$i", effectiveTime = now.plusSeconds(i * 60L)) }
        val state = LiveDeparturesState.Live(snapshot(*departures.toTypedArray()))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(2, content.departures.size)
    }

    @Test
    fun `Live sorts by effective time before applying the two-departure cap`() {
        val third = prepared(departureId = "third", effectiveTime = now.plusSeconds(900))
        val first = prepared(departureId = "first", effectiveTime = now.plusSeconds(60))
        val second = prepared(departureId = "second", effectiveTime = now.plusSeconds(300))
        val state = LiveDeparturesState.Live(snapshot(third, first, second))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(listOf(now.plusSeconds(60), now.plusSeconds(300)), content.departures.map { it.effectiveTime })
    }

    @Test
    fun `a departure whose effective time has already passed is dropped`() {
        val past = prepared(departureId = "past", effectiveTime = now.minusSeconds(60))
        val future = prepared(departureId = "future", effectiveTime = now.plusSeconds(60))
        val state = LiveDeparturesState.Live(snapshot(past, future))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(listOf(now.plusSeconds(60)), content.departures.map { it.effectiveTime })
    }

    // ---- Expired Live snapshot must become NoUpcomingDepartures, never Live(emptyList()) ----

    @Test
    fun `a Live snapshot in which every departure is now in the past becomes NoUpcomingDepartures`() {
        val allExpired = snapshot(
            prepared(departureId = "a", effectiveTime = now.minusSeconds(60)),
            prepared(departureId = "b", effectiveTime = now.minusSeconds(30)),
        )
        val state = LiveDeparturesState.Live(allExpired)
        val content = RoutineNotificationMapper.map(routine(), state, now).content
        assertTrue(
            "expected NoUpcomingDepartures, got $content",
            content is RoutineNotificationContent.NoUpcomingDepartures,
        )
    }

    @Test
    fun `NoUpcomingDepartures produced from an expired Live snapshot carries the snapshot's fetchedAt`() {
        val fetchedAt = now.minusSeconds(600)
        val allExpired = snapshot(prepared(effectiveTime = now.minusSeconds(60)), fetchedAt = fetchedAt)
        val state = LiveDeparturesState.Live(allExpired)
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.NoUpcomingDepartures
        assertEquals(fetchedAt, content.lastCheckedAt)
    }

    @Test
    fun `a departure exactly at now remains Live with a zero-minute countdown, not NoUpcomingDepartures`() {
        val d = prepared(effectiveTime = now)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content
        assertTrue("expected Live, got $content", content is RoutineNotificationContent.Live)
        assertEquals(0L, (content as RoutineNotificationContent.Live).departures.single().minutesRemaining)
    }

    @Test
    fun `a mixed snapshot of past and future departures remains Live with only the valid rows`() {
        val past = prepared(departureId = "past", effectiveTime = now.minusSeconds(60))
        val future = prepared(departureId = "future", effectiveTime = now.plusSeconds(60))
        val state = LiveDeparturesState.Live(snapshot(past, future))
        val content = RoutineNotificationMapper.map(routine(), state, now).content
        assertTrue("expected Live, got $content", content is RoutineNotificationContent.Live)
        assertEquals(1, (content as RoutineNotificationContent.Live).departures.size)
    }

    // ---- Countdown recalculation (must never trust PreparedDeparture.minutesRemaining) ----

    @Test
    fun `a departure exactly at now has zero minutes remaining`() {
        val d = prepared(effectiveTime = now, minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(0L, content.departures.single().minutesRemaining)
    }

    @Test
    fun `a departure 30 seconds away rounds up to 1 minute`() {
        val d = prepared(effectiveTime = now.plusSeconds(30), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(1L, content.departures.single().minutesRemaining)
    }

    @Test
    fun `a departure exactly 60 seconds away is 1 minute`() {
        val d = prepared(effectiveTime = now.plusSeconds(60), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(1L, content.departures.single().minutesRemaining)
    }

    @Test
    fun `a departure 61 seconds away rounds up to 2 minutes`() {
        val d = prepared(effectiveTime = now.plusSeconds(61), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals(2L, content.departures.single().minutesRemaining)
    }

    @Test
    fun `countdown is recomputed against the mapper's own now, not the stale cached value`() {
        // Simulates a debug-trigger tap happening some time after the underlying fetch: the
        // cached minutesRemaining (999) must be ignored entirely in favour of a fresh
        // computation against `laterNow`.
        val fetchedAt = now
        val laterNow = now.plusSeconds(120)
        val d = prepared(effectiveTime = now.plusSeconds(300), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d, fetchedAt = fetchedAt))
        val content = RoutineNotificationMapper.map(routine(), state, laterNow).content as RoutineNotificationContent.Live
        assertEquals(3L, content.departures.single().minutesRemaining) // 180s remaining -> ceil to 3 min
    }

    // ---- Real-time vs scheduled-only ----

    @Test
    fun `a real-time departure is marked real-time`() {
        val d = prepared(isRealTime = true)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertTrue(content.departures.single().isRealTime)
    }

    @Test
    fun `a scheduled-only departure is marked not real-time`() {
        val d = prepared(isRealTime = false)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertFalse(content.departures.single().isRealTime)
    }

    // ---- Cancellation ----

    @Test
    fun `a future cancelled departure is preserved and flagged`() {
        val d = prepared(effectiveTime = now.plusSeconds(300), isCancelled = true)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertTrue(content.departures.single().isCancelled)
    }

    // ---- Missing destination fallback ----

    @Test
    fun `a departure with neither destination nor direction maps to a null destinationLabel`() {
        val d = prepared(destination = null, direction = null)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertNull(content.departures.single().destinationLabel)
    }

    @Test
    fun `direction is used as a fallback when destination is missing`() {
        val d = prepared(destination = null, direction = "Southbound")
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Live
        assertEquals("Southbound", content.departures.single().destinationLabel)
    }

    // ---- NoUpcomingDepartures ----

    @Test
    fun `NoUpcomingDepartures maps with its fetchedAt as lastCheckedAt`() {
        val state = LiveDeparturesState.NoUpcomingDepartures(fetchedAt = now)
        val content = RoutineNotificationMapper.map(routine(), state, now).content
        assertEquals(RoutineNotificationContent.NoUpcomingDepartures(now), content)
    }

    // ---- Offline / Unavailable ----

    @Test
    fun `Offline maps to Offline content`() {
        val content = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Offline, now).content
        assertEquals(RoutineNotificationContent.Offline, content)
    }

    @Test
    fun `Unavailable maps to Unavailable content`() {
        val content = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Unavailable, now).content
        assertEquals(RoutineNotificationContent.Unavailable, content)
    }

    // ---- Stale ----

    @Test
    fun `Stale carries its departures and lastCheckedAt`() {
        val fetchedAt = now.minusSeconds(600)
        val d = prepared(effectiveTime = now.plusSeconds(120))
        val state = LiveDeparturesState.Stale(snapshot(d, fetchedAt = fetchedAt))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Stale
        assertEquals(fetchedAt, content.lastCheckedAt)
        assertEquals(1, content.departures.size)
    }

    @Test
    fun `a stale snapshot containing newly-expired departures has them filtered out`() {
        // The snapshot was valid when fetched, but enough time has passed that one of its
        // departures has since departed -- it must not appear in the notification.
        val fetchedAt = now.minusSeconds(600)
        val nowExpired = prepared(departureId = "expired", effectiveTime = now.minusSeconds(30))
        val stillUpcoming = prepared(departureId = "upcoming", effectiveTime = now.plusSeconds(120))
        val state = LiveDeparturesState.Stale(snapshot(nowExpired, stillUpcoming, fetchedAt = fetchedAt))
        val content = RoutineNotificationMapper.map(routine(), state, now).content as RoutineNotificationContent.Stale
        assertEquals(1, content.departures.size)
        assertEquals(now.plusSeconds(120), content.departures.single().effectiveTime)
    }

    @Test
    fun `a stale snapshot where every departure has expired remains Stale, not NoUpcomingDepartures, with an empty row list`() {
        // Unlike an expired Live snapshot (which is re-reported as NoUpcomingDepartures — see
        // above), a Stale snapshot must keep communicating "the last refresh failed", even
        // once its own cached departures have all since expired too. Collapsing it into
        // NoUpcomingDepartures here would incorrectly claim the data is current.
        val fetchedAt = now.minusSeconds(600)
        val expired = prepared(effectiveTime = now.minusSeconds(30))
        val state = LiveDeparturesState.Stale(snapshot(expired, fetchedAt = fetchedAt))
        val content = RoutineNotificationMapper.map(routine(), state, now).content
        assertTrue("expected Stale, got $content", content is RoutineNotificationContent.Stale)
        content as RoutineNotificationContent.Stale
        assertTrue(content.departures.isEmpty())
        assertEquals(fetchedAt, content.lastCheckedAt)
    }

    // ---- topDisruption ----

    @Test
    fun `no topDisruption produces null disruptionHeadline and disruptionDetails`() {
        val model = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Loading, now)
        assertNull(model.disruptionHeadline)
        assertNull(model.disruptionDetails)
    }

    @Test
    fun `topDisruption's header and details are carried into the model unchanged`() {
        val d = disruption(header = "Delays on line 14", details = "Expect longer travel times.")
        val model = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Loading, now, topDisruption = d)
        assertEquals("Delays on line 14", model.disruptionHeadline)
        assertEquals("Expect longer travel times.", model.disruptionDetails)
    }

    @Test
    fun `topDisruption is independent of the departures content state`() {
        val d = disruption()
        val model = RoutineNotificationMapper.map(routine(), LiveDeparturesState.Offline, now, topDisruption = d)
        assertEquals(RoutineNotificationContent.Offline, model.content)
        assertEquals(d.message.header, model.disruptionHeadline)
    }
}
