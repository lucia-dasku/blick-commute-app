package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Pure JVM tests for [RoutineWidgetMapper] — no Android dependency, no Robolectric, no widget
 * instance. Mirrors `RoutineNotificationMapperTest`'s structure exactly, since both mappers
 * apply the same rules to the same [LiveDeparturesState] input.
 */
class RoutineWidgetMapperTest {

    private val now = Instant.parse("2026-07-28T08:00:00Z")

    private fun routine(
        id: String = "r1",
        name: String = "Morning commute",
        siteName: String = "Fruängen",
        destinationLabel: String? = "Fruängen",
        transportMode: TransportMode = TransportMode.METRO,
        lineDesignation: String? = "14",
    ) = CommuteRoutine(
        id = id,
        name = name,
        siteId = 9145,
        siteName = siteName,
        transportMode = transportMode,
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

    // ---- Routine identity fields ----

    @Test
    fun `routine id, name, station and direction are carried into the model`() {
        val model = RoutineWidgetMapper.map(
            routine(id = "r42", name = "Evening commute", siteName = "Slussen", destinationLabel = "T-Centralen"),
            LiveDeparturesState.Loading,
            now,
        )
        assertEquals("r42", model.routineId)
        assertEquals("Evening commute", model.routineName)
        assertEquals("Slussen", model.stationName)
        assertEquals("T-Centralen", model.directionLabel)
    }

    @Test
    fun `a missing pinned direction falls back to null, not an empty string`() {
        val model = RoutineWidgetMapper.map(routine(destinationLabel = null), LiveDeparturesState.Loading, now)
        assertNull(model.directionLabel)
    }

    @Test
    fun `the routine's own line designation and transport mode are carried into the model, for the header badge`() {
        val model = RoutineWidgetMapper.map(
            routine(transportMode = TransportMode.TRAIN, lineDesignation = "43X"),
            LiveDeparturesState.Loading,
            now,
        )
        assertEquals("43X", model.lineDesignation)
        assertEquals(TransportMode.TRAIN, model.transportMode)
    }

    @Test
    fun `a routine with no pinned line designation carries null, not an empty string`() {
        val model = RoutineWidgetMapper.map(routine(lineDesignation = null), LiveDeparturesState.Loading, now)
        assertNull(model.lineDesignation)
    }

    // ---- Loading ----

    @Test
    fun `Loading maps to Loading content`() {
        val model = RoutineWidgetMapper.map(routine(), LiveDeparturesState.Loading, now)
        assertEquals(RoutineWidgetContent.Loading, model.content)
    }

    // ---- Live: next and following ----

    @Test
    fun `Live with one departure has a next but no following`() {
        val state = LiveDeparturesState.Live(snapshot(prepared(effectiveTime = now.plusSeconds(120))))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertEquals(2L, content.next.minutesRemaining)
        assertNull(content.following)
    }

    @Test
    fun `Live with two departures fills both next and following, sorted by effective time`() {
        val later = prepared(departureId = "later", effectiveTime = now.plusSeconds(600))
        val sooner = prepared(departureId = "sooner", effectiveTime = now.plusSeconds(120))
        val state = LiveDeparturesState.Live(snapshot(later, sooner))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertEquals(2L, content.next.minutesRemaining)
        assertEquals(10L, content.following?.minutesRemaining)
    }

    @Test
    fun `Live is capped at next plus following even with more upcoming departures`() {
        val departures = (1..5).map { i -> prepared(departureId = "dep-$i", effectiveTime = now.plusSeconds(i * 60L)) }
        val state = LiveDeparturesState.Live(snapshot(*departures.toTypedArray()))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        // Only the two soonest surface as next/following -- there is no way to represent a
        // third in this model, matching the product doc's "next departure" + "following
        // departure" wording exactly.
        assertEquals(1L, content.next.minutesRemaining)
        assertEquals(2L, content.following?.minutesRemaining)
    }

    // ---- Expired-departure filtering (never present an expired departure as current) ----

    @Test
    fun `a departure whose effective time has already passed is dropped from next`() {
        val past = prepared(departureId = "past", effectiveTime = now.minusSeconds(60))
        val future = prepared(departureId = "future", effectiveTime = now.plusSeconds(60))
        val state = LiveDeparturesState.Live(snapshot(past, future))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertEquals(1L, content.next.minutesRemaining)
        assertNull(content.following)
    }

    @Test
    fun `a Live snapshot in which every departure is now in the past becomes NoUpcomingDepartures, not an empty Live`() {
        val allExpired = snapshot(
            prepared(departureId = "a", effectiveTime = now.minusSeconds(60)),
            prepared(departureId = "b", effectiveTime = now.minusSeconds(30)),
        )
        val content = RoutineWidgetMapper.map(routine(), LiveDeparturesState.Live(allExpired), now).content
        assertTrue("expected NoUpcomingDepartures, got $content", content is RoutineWidgetContent.NoUpcomingDepartures)
    }

    @Test
    fun `a departure exactly at now remains Live with a zero-minute countdown, not filtered out`() {
        val d = prepared(effectiveTime = now)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineWidgetMapper.map(routine(), state, now).content
        assertTrue(content is RoutineWidgetContent.Live)
        assertEquals(0L, (content as RoutineWidgetContent.Live).next.minutesRemaining)
    }

    // ---- Countdown recalculation (must never trust PreparedDeparture.minutesRemaining) ----

    @Test
    fun `countdown is recomputed against the mapper's own now, never the stale cached value`() {
        val fetchedAt = now
        val laterNow = now.plusSeconds(120)
        val d = prepared(effectiveTime = now.plusSeconds(300), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d, fetchedAt = fetchedAt))
        val content = RoutineWidgetMapper.map(routine(), state, laterNow).content as RoutineWidgetContent.Live
        assertEquals(3L, content.next.minutesRemaining) // 180s remaining -> ceil to 3 min
    }

    @Test
    fun `a departure 30 seconds away rounds up to 1 minute`() {
        val d = prepared(effectiveTime = now.plusSeconds(30), minutesRemaining = 999L)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertEquals(1L, content.next.minutesRemaining)
    }

    // ---- Real-time / cancelled flags ----

    @Test
    fun `a real-time departure is marked real-time`() {
        val state = LiveDeparturesState.Live(snapshot(prepared(isRealTime = true)))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertTrue(content.next.isRealTime)
    }

    @Test
    fun `a cancelled departure is preserved and flagged, not dropped`() {
        val d = prepared(effectiveTime = now.plusSeconds(300), isCancelled = true)
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertTrue(content.next.isCancelled)
    }

    // ---- Missing destination fallback ----

    @Test
    fun `direction is used as a fallback when destination is missing`() {
        val d = prepared(destination = null, direction = "Southbound")
        val state = LiveDeparturesState.Live(snapshot(d))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Live
        assertEquals("Southbound", content.next.destinationLabel)
    }

    // ---- NoUpcomingDepartures / Offline / Unavailable ----

    @Test
    fun `NoUpcomingDepartures maps with its fetchedAt as lastCheckedAt`() {
        val content = RoutineWidgetMapper.map(routine(), LiveDeparturesState.NoUpcomingDepartures(now), now).content
        assertEquals(RoutineWidgetContent.NoUpcomingDepartures(now), content)
    }

    @Test
    fun `Offline maps to Offline content`() {
        val content = RoutineWidgetMapper.map(routine(), LiveDeparturesState.Offline, now).content
        assertEquals(RoutineWidgetContent.Offline, content)
    }

    @Test
    fun `Unavailable maps to Unavailable content`() {
        val content = RoutineWidgetMapper.map(routine(), LiveDeparturesState.Unavailable, now).content
        assertEquals(RoutineWidgetContent.Unavailable, content)
    }

    // ---- Stale ----

    @Test
    fun `Stale carries its next departure and lastCheckedAt`() {
        val fetchedAt = now.minusSeconds(600)
        val d = prepared(effectiveTime = now.plusSeconds(120))
        val state = LiveDeparturesState.Stale(snapshot(d, fetchedAt = fetchedAt))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Stale
        assertEquals(fetchedAt, content.lastCheckedAt)
        assertEquals(2L, content.next?.minutesRemaining)
    }

    @Test
    fun `a stale snapshot containing a newly-expired departure has it filtered from next and following`() {
        val fetchedAt = now.minusSeconds(600)
        val nowExpired = prepared(departureId = "expired", effectiveTime = now.minusSeconds(30))
        val stillUpcoming = prepared(departureId = "upcoming", effectiveTime = now.plusSeconds(120))
        val state = LiveDeparturesState.Stale(snapshot(nowExpired, stillUpcoming, fetchedAt = fetchedAt))
        val content = RoutineWidgetMapper.map(routine(), state, now).content as RoutineWidgetContent.Stale
        assertEquals(2L, content.next?.minutesRemaining)
        assertNull(content.following)
    }

    @Test
    fun `a stale snapshot where every departure has expired remains Stale, not NoUpcomingDepartures, with null next and following`() {
        // Matches RoutineNotificationMapper's identical rule: Stale must keep communicating
        // "the last refresh failed," even once its own cached departures have all expired too
        // -- collapsing to NoUpcomingDepartures here would incorrectly claim the data is current.
        val fetchedAt = now.minusSeconds(600)
        val expired = prepared(effectiveTime = now.minusSeconds(30))
        val state = LiveDeparturesState.Stale(snapshot(expired, fetchedAt = fetchedAt))
        val content = RoutineWidgetMapper.map(routine(), state, now).content
        assertTrue("expected Stale, got $content", content is RoutineWidgetContent.Stale)
        content as RoutineWidgetContent.Stale
        assertNull(content.next)
        assertNull(content.following)
        assertEquals(fetchedAt, content.lastCheckedAt)
    }

    // ---- notificationsUnavailable() -- no LiveDeparturesState counterpart (widget-only case) ----

    @Test
    fun `notificationsUnavailable carries the routine's identity fields with NotificationsUnavailable content`() {
        val model = RoutineWidgetMapper.notificationsUnavailable(
            routine(id = "r42", name = "Evening commute", siteName = "Slussen", destinationLabel = "T-Centralen"),
        )
        assertEquals("r42", model.routineId)
        assertEquals("Evening commute", model.routineName)
        assertEquals("Slussen", model.stationName)
        assertEquals("T-Centralen", model.directionLabel)
        assertEquals(RoutineWidgetContent.NotificationsUnavailable, model.content)
    }

    @Test
    fun `notificationsUnavailable falls back direction to null, not an empty string, exactly like map`() {
        val model = RoutineWidgetMapper.notificationsUnavailable(routine(destinationLabel = null))
        assertNull(model.directionLabel)
    }

    @Test
    fun `notificationsUnavailable also carries the routine's own line designation and transport mode`() {
        val model = RoutineWidgetMapper.notificationsUnavailable(routine(transportMode = TransportMode.TRAIN, lineDesignation = "42X"))
        assertEquals("42X", model.lineDesignation)
        assertEquals(TransportMode.TRAIN, model.transportMode)
    }
}
