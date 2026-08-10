package se.blick.app.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime

class RoutineTierPolicyTest {
    private fun routine(id: String, type: RoutineType) = CommuteRoutine(
        id = id, name = id, siteId = 1, siteName = "A", transportMode = TransportMode.BUS,
        lineId = 1, lineDesignation = "1", directionCode = 1, destinationLabel = "B",
        activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(8, 0), type = type,
    )

    @Test fun `revocation locks premium routines without deleting and selected line routine keeps running`() {
        val line = routine("line", RoutineType.LINE_DIRECTION)
        val exact = routine("exact", RoutineType.EXACT_DESTINATION)
        val routines = listOf(exact, line)
        assertTrue(RoutineTierPolicy.canRun(line, routines, EntitlementState.Free, "line"))
        assertFalse(RoutineTierPolicy.canRun(exact, routines, EntitlementState.Free, "line"))
        assertTrue(routines.contains(exact))
    }
}
