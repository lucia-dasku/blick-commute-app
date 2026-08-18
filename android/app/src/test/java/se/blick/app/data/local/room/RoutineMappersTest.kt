package se.blick.app.data.local.room

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals

class RoutineMappersTest {

    @Test
    fun `every optional routine label round-trips through the entity`() {
        for (label in RoutineLabel.entries) {
            val routine = sampleRoutine().copy(label = label)

            assertEquals(label, routine.toEntity().toDomain().label)
        }
        assertEquals(null, sampleRoutine().toEntity().toDomain().label)
    }

    @Test
    fun `an unrecognized persisted label is treated as no label`() {
        val entity = sampleRoutine().toEntity().copy(label = "NOT_A_LABEL")

        assertEquals(null, entity.toDomain().label)
    }

    private fun sampleRoutine() = CommuteRoutine(
        name = "Morning commute",
        siteId = 1,
        siteName = "Origin",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "Destination",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(8, 0),
    )

    @Test
    fun `round-trips a routine with a multi-day active set and a paused date through entity and back`() {
        val original = CommuteRoutine(
            id = "abc-123",
            name = "Evening commute",
            siteId = 9192,
            siteName = "Slussen",
            transportMode = TransportMode.BUS,
            lineId = 401,
            lineDesignation = "401",
            directionCode = 2,
            destinationLabel = "Ekstubben",
            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
            startTime = LocalTime.of(16, 45),
            endTime = LocalTime.of(17, 30),
            enabled = false,
            pausedDate = LocalDate.of(2026, 8, 3),
        )

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `round-trips a routine with no active days, no line, and no paused date`() {
        val original = CommuteRoutine(
            id = "empty-days",
            name = "Draft routine",
            siteId = 1,
            siteName = "Test site",
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            activeDays = emptySet(),
            startTime = LocalTime.MIDNIGHT,
            endTime = LocalTime.of(23, 59),
            enabled = true,
            pausedDate = null,
        )

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `active days mask is order-independent and covers every day of the week`() {
        for (day in DayOfWeek.entries) {
            val routine = CommuteRoutine(
                id = "d-${day.name}",
                name = "x",
                siteId = 1,
                siteName = "x",
                transportMode = TransportMode.METRO,
                lineId = null,
                lineDesignation = null,
                directionCode = null,
                destinationLabel = null,
                activeDays = setOf(day),
                startTime = LocalTime.of(7, 0),
                endTime = LocalTime.of(8, 0),
            )
            assertEquals(setOf(day), routine.toEntity().toDomain().activeDays)
        }
    }

    @Test
    fun `selected exact journey transport modes round-trip`() {
        val routine = CommuteRoutine(
            name = "Train and bus",
            siteId = 1,
            siteName = "Origin",
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            activeDays = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(8, 0),
            allowedJourneyTransportModes = setOf(TransportMode.TRAIN, TransportMode.BUS),
        )

        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), routine.toEntity().toDomain().allowedJourneyTransportModes)
    }

    @Test
    fun `a new exact-destination routine defaults to BOTH changes preference`() {
        val routine = CommuteRoutine(
            name = "Airport commute",
            siteId = 1,
            siteName = "Origin",
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            activeDays = setOf(DayOfWeek.MONDAY),
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(8, 0),
        )

        assertEquals(ExactDestinationChangesPreference.BOTH, routine.changesPreference)
        assertEquals(ExactDestinationChangesPreference.BOTH, routine.toEntity().toDomain().changesPreference)
    }

    @Test
    fun `every changesPreference value round-trips through the entity exactly`() {
        for (preference in ExactDestinationChangesPreference.entries) {
            val routine = CommuteRoutine(
                id = "pref-${preference.name}",
                name = "x",
                siteId = 1,
                siteName = "x",
                transportMode = TransportMode.UNKNOWN,
                lineId = null,
                lineDesignation = null,
                directionCode = null,
                destinationLabel = null,
                activeDays = setOf(DayOfWeek.MONDAY),
                startTime = LocalTime.of(7, 0),
                endTime = LocalTime.of(8, 0),
                changesPreference = preference,
            )

            assertEquals(preference, routine.toEntity().toDomain().changesPreference)
        }
    }

    @Test
    fun `an unrecognized persisted changesPreference value defaults to BOTH rather than crashing`() {
        val entity = CommuteRoutine(
            name = "x", siteId = 1, siteName = "x", transportMode = TransportMode.UNKNOWN,
            lineId = null, lineDesignation = null, directionCode = null, destinationLabel = null,
            activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(8, 0),
        ).toEntity().copy(changesPreference = "SOMETHING_UNRECOGNIZED")

        assertEquals(ExactDestinationChangesPreference.BOTH, entity.toDomain().changesPreference)
    }
}
