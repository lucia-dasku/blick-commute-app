package se.blick.app.ui.screens.routinelist

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

class RoutineListFormattingTest {
    private val routine = CommuteRoutine(
        name = "Morning commute",
        siteId = 1,
        siteName = "Origin",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "Destination",
        activeDays = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        ),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 30),
    )

    @Test
    fun `weekday schedule uses the compact card hierarchy format`() {
        assertEquals(
            "Weekdays · 07:00–09:30",
            formatRoutineCardSchedule(routine, Locale.ENGLISH, "Every day", "Weekdays"),
        )
    }

    @Test
    fun `schedule accepts localized day-group labels`() {
        assertEquals(
            "Vardagar · 07:00–09:30",
            formatRoutineCardSchedule(routine, Locale.forLanguageTag("sv"), "Varje dag", "Vardagar"),
        )
    }

    @Test
    fun `light city canvas keeps routine section labels dark`() {
        assertEquals(
            Color.Black,
            routineListSectionLabelColor(
                useStockholmNightHeader = false,
                backgroundColor = Color(0xFFFAF4F3),
            ),
        )
    }
}
