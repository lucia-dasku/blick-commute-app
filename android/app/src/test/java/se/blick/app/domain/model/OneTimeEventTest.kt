package se.blick.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class OneTimeEventTest {
    private val now = Instant.parse("2026-09-01T10:00:00Z")
    private val origin = JourneyLocation("A", "Home")
    private val destination = JourneyLocation("B", "Globen")

    @Test fun `event categories are the four dedicated presentation labels`() {
        assertEquals(
            listOf("TRAVEL", "EVENT", "APPOINTMENT", "OTHER"),
            OneTimeEventLabel.entries.map { it.name },
        )
    }

    @Test fun `future event is valid for both time types`() {
        OneTimeEventTimeType.entries.forEach {
            assertEquals(
                OneTimeEventValidationResult.VALID,
                validateOneTimeEvent(origin, destination, LocalDate.of(2026, 9, 17), LocalTime.of(18, 30), now),
            )
        }
    }

    @Test fun `past date and time is rejected`() {
        assertEquals(
            OneTimeEventValidationResult.PAST,
            validateOneTimeEvent(origin, destination, LocalDate.of(2026, 8, 31), LocalTime.NOON, now),
        )
    }

    @Test fun `upcoming events exclude past and sort chronologically`() {
        val early = event("early", LocalDate.of(2026, 9, 2), LocalTime.of(9, 0))
        val late = event("late", LocalDate.of(2026, 9, 3), LocalTime.of(9, 0))
        val past = event("past", LocalDate.of(2026, 8, 31), LocalTime.of(9, 0))
        assertEquals(listOf("early", "late"), listOf(late, past, early).upcomingAt(now).map(OneTimeEvent::id))
    }

    private fun event(id: String, date: LocalDate, time: LocalTime) = OneTimeEvent(
        id = id,
        label = OneTimeEventLabel.EVENT,
        name = id,
        originId = origin.id,
        originName = origin.name,
        destinationId = destination.id,
        destinationName = destination.name,
        date = date,
        time = time,
    )
}
