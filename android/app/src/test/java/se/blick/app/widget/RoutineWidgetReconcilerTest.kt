package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pure JVM tests for [decideReconciledWidgetState] — no Android dependency, no repository, no
 * [se.blick.app.data.repository.RoutineRepository] fake needed, since it's a plain function over
 * an already-loaded routine list. Reuses [se.blick.app.scheduling.NextOccurrenceCalculator], the
 * exact same active-window calculation the worker itself uses.
 */
class RoutineWidgetReconcilerTest {

    private fun routine(
        id: String = "r1",
        enabled: Boolean = true,
        startTime: LocalTime = LocalTime.of(7, 0),
        endTime: LocalTime = LocalTime.of(9, 0),
        activeDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        pausedDate: LocalDate? = null,
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = activeDays,
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
        pausedDate = pausedDate,
    )

    // Monday 08:00 UTC -- inside the 07:00-09:00 window.
    private val activeNow = ZonedDateTime.of(2026, 7, 27, 8, 0, 0, 0, ZoneOffset.UTC)

    // Monday 10:00 UTC -- after the window has closed for the day.
    private val outsideWindow = ZonedDateTime.of(2026, 7, 27, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `no routines at all -- NoActiveCommute`() {
        val state = decideReconciledWidgetState(emptyList(), activeNow)
        assertEquals(RoutineWidgetUiState.NoActiveCommute, state)
    }

    @Test
    fun `a disabled routine inside its own window -- still NoActiveCommute`() {
        val state = decideReconciledWidgetState(listOf(routine(enabled = false)), activeNow)
        assertEquals(RoutineWidgetUiState.NoActiveCommute, state)
    }

    @Test
    fun `an enabled routine outside its window -- NoActiveCommute`() {
        val state = decideReconciledWidgetState(listOf(routine()), outsideWindow)
        assertEquals(RoutineWidgetUiState.NoActiveCommute, state)
    }

    @Test
    fun `an enabled routine inside its active window -- ActiveRoutine with Loading content`() {
        val r = routine()
        val state = decideReconciledWidgetState(listOf(r), activeNow)
        assertTrue(state is RoutineWidgetUiState.ActiveRoutine)
        state as RoutineWidgetUiState.ActiveRoutine
        assertEquals(r.id, state.model.routineId)
        assertEquals(RoutineWidgetContent.Loading, state.model.content)
    }

    @Test
    fun `an enabled routine paused for today, even during its usual window -- NoActiveCommute`() {
        val r = routine(pausedDate = activeNow.toLocalDate())
        val state = decideReconciledWidgetState(listOf(r), activeNow)
        assertEquals(RoutineWidgetUiState.NoActiveCommute, state)
    }

    @Test
    fun `only an enabled routine is considered when a disabled one is also present`() {
        val disabled = routine(id = "disabled", enabled = false)
        val enabled = routine(id = "enabled", enabled = true)
        val state = decideReconciledWidgetState(listOf(disabled, enabled), activeNow)
        assertTrue(state is RoutineWidgetUiState.ActiveRoutine)
        assertEquals("enabled", (state as RoutineWidgetUiState.ActiveRoutine).model.routineId)
    }
}
