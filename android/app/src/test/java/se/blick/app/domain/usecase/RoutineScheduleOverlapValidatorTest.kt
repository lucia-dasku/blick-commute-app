package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime

class RoutineScheduleOverlapValidatorTest {
    private fun routine(id: String, day: DayOfWeek, start: String, end: String) = CommuteRoutine(
        id = id, name = id, siteId = 1, siteName = "Stop", transportMode = TransportMode.BUS,
        lineId = 1, lineDesignation = "1", directionCode = 1, destinationLabel = "End",
        activeDays = setOf(day), startTime = LocalTime.parse(start), endTime = LocalTime.parse(end),
    )

    @Test fun `same day overlap is rejected`() {
        assertTrue(RoutineScheduleOverlapValidator.validate(
            routine("new", DayOfWeek.MONDAY, "08:00", "09:00"),
            listOf(routine("old", DayOfWeek.MONDAY, "08:30", "10:00")),
        ) is RoutineOverlapValidationResult.Overlaps)
    }

    @Test fun `overnight overlap uses following weekday`() {
        assertTrue(RoutineScheduleOverlapValidator.validate(
            routine("new", DayOfWeek.TUESDAY, "00:30", "01:30"),
            listOf(routine("old", DayOfWeek.MONDAY, "23:00", "01:00")),
        ) is RoutineOverlapValidationResult.Overlaps)
    }

    @Test fun `sunday overnight wraps into monday`() {
        assertTrue(RoutineScheduleOverlapValidator.validate(
            routine("new", DayOfWeek.MONDAY, "00:15", "00:45"),
            listOf(routine("old", DayOfWeek.SUNDAY, "23:30", "00:30")),
        ) is RoutineOverlapValidationResult.Overlaps)
    }

    @Test fun `touching endpoints do not overlap`() {
        assertEquals(RoutineOverlapValidationResult.Valid, RoutineScheduleOverlapValidator.validate(
            routine("new", DayOfWeek.MONDAY, "09:00", "10:00"),
            listOf(routine("old", DayOfWeek.MONDAY, "08:00", "09:00")),
        ))
    }
}
