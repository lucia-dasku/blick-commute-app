package se.blick.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import java.time.DayOfWeek
import java.time.LocalTime

class RoutineDurationValidatorTest {

    private fun routine(
        id: String = "r1",
        activeDays: Set<DayOfWeek>,
        startTime: LocalTime,
        endTime: LocalTime,
        enabled: Boolean = true,
    ) = CommuteRoutine(
        id = id,
        name = "Test routine",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.BUS,
        lineId = 705,
        lineDesignation = "705",
        directionCode = 1,
        destinationLabel = "Segeltorp",
        activeDays = activeDays,
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
    )

    private fun assertValid(result: RoutineDurationValidationResult) {
        assertEquals(RoutineDurationValidationResult.Valid, result)
    }

    private fun assertExceeds(result: RoutineDurationValidationResult, day: DayOfWeek, totalMinutes: Int) {
        assertEquals(RoutineDurationValidationResult.ExceedsDailyLimit(day, totalMinutes), result)
    }

    // ---- Single routine, no others ----

    @Test
    fun `exactly 300 minutes alone is accepted`() {
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(7, 0),
            proposedEndTime = LocalTime.of(12, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = emptyList(),
        )
        assertValid(result)
    }

    @Test
    fun `301 minutes alone is rejected`() {
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(7, 0),
            proposedEndTime = LocalTime.of(12, 1),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = emptyList(),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 301)
    }

    @Test
    fun `a 90-minute routine alone is accepted`() {
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(7, 30),
            proposedEndTime = LocalTime.of(9, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = emptyList(),
        )
        assertValid(result)
    }

    // ---- Multiple enabled routines summed for the same weekday ----

    @Test
    fun `two existing routines summing to exactly 300 minutes with the proposed one is accepted`() {
        // Mon 07:00-09:00 (2h) existing + Mon 16:00-19:00 (3h) proposed = 5h exactly.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(9, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(16, 0),
            proposedEndTime = LocalTime.of(19, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existing),
        )
        assertValid(result)
    }

    @Test
    fun `a third routine pushing the same weekday over the limit is rejected`() {
        // Mon 07:00-09:00 (2h) + Mon 16:00-19:00 (3h) already stored = 5h; proposing a third,
        // Mon 20:00-21:00 (1h), pushes the day to 6h -- must be rejected.
        val existingA = routine(id = "existing-a", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(9, 0))
        val existingB = routine(id = "existing-b", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(16, 0), endTime = LocalTime.of(19, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(20, 0),
            proposedEndTime = LocalTime.of(21, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existingA, existingB),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 360)
    }

    // ---- Different weekdays evaluated independently ----

    @Test
    fun `routines on different weekdays do not combine`() {
        // 4h existing on Monday only + 4h proposed on Tuesday only -- neither day exceeds 5h
        // even though the two durations would together if summed blindly.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(6, 0),
            proposedEndTime = LocalTime.of(10, 0),
            proposedActiveDays = setOf(DayOfWeek.TUESDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existing),
        )
        assertValid(result)
    }

    @Test
    fun `a proposed routine spanning two weekdays is rejected only for the day that combines over the limit`() {
        // Existing 4h routine active Monday only. Proposed 4h routine active BOTH Monday and
        // Tuesday: Monday totals 8h (invalid), Tuesday totals 4h alone (would be fine) -- the
        // Monday violation must still surface even though Tuesday alone is fine.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(6, 0),
            proposedEndTime = LocalTime.of(10, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existing),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 480)
    }

    @Test
    fun `Monday 18-00-23-00 plus Tuesday 00-00-05-00 is rejected by the rolling-24-hour check`() {
        // The exact motivating example for the rolling-24h redesign: each day's own total is
        // only 5h (300min), which a per-weekday-only check would accept -- but the two
        // occurrences sit only an hour apart, so a window from Monday 18:00 to Tuesday 18:00
        // contains both in full: 10 real configured hours inside one rolling 24-hour span.
        val monday = routine(id = "monday", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(18, 0), endTime = LocalTime.of(23, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(0, 0),
            proposedEndTime = LocalTime.of(5, 0),
            proposedActiveDays = setOf(DayOfWeek.TUESDAY),
            proposedEnabled = true,
            existingRoutines = listOf(monday),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 600)
    }

    @Test
    fun `the equivalent Sunday-to-Monday week-boundary case is also rejected`() {
        // Same shape as the Monday-Tuesday example, but spanning the week wrap (Sunday into
        // Monday) -- proves the rolling-24h scan correctly sees across the cycle boundary, not
        // just between "normal" adjacent weekdays in the middle of the week.
        val sunday = routine(id = "sunday", activeDays = setOf(DayOfWeek.SUNDAY), startTime = LocalTime.of(18, 0), endTime = LocalTime.of(23, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(0, 0),
            proposedEndTime = LocalTime.of(5, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(sunday),
        )
        assertExceeds(result, DayOfWeek.SUNDAY, 600)
    }

    // ---- Disabled routines ----

    @Test
    fun `a disabled existing routine does not count toward the total`() {
        // Existing disabled 4h routine + proposed 3h routine, same weekday -- would be 7h (over
        // the limit) if the disabled routine counted, but it must not.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0), enabled = false)
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(16, 0),
            proposedEndTime = LocalTime.of(19, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existing),
        )
        assertValid(result)
    }

    @Test
    fun `a disabled proposed routine is still rejected when its own duration exceeds the limit`() {
        // Regression: a routine disabled today can be re-enabled later without ever going
        // through this validator again (see RoutineDetailsViewModel.toggleEnabled) -- if an
        // over-limit duration were accepted merely because it's disabled, re-enabling it would
        // silently fail to schedule with no user-visible feedback. The proposal's own duration
        // must always be checked, regardless of enabled state.
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(6, 0),
            proposedEndTime = LocalTime.of(13, 0), // 7h, well over the limit on its own
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = false,
            existingRoutines = emptyList(),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 420)
    }

    @Test
    fun `a disabled proposed routine with a valid own duration is still exempt from combining with others`() {
        // Existing ENABLED routine already uses 4h on Monday. The proposed routine is also 4h
        // on Monday, which would exceed the limit if summed (8h) -- but since the proposal
        // itself is disabled, it doesn't compete for Monday's budget right now, only its own
        // (valid, 4h) duration is checked.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(16, 0),
            proposedEndTime = LocalTime.of(20, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = false,
            existingRoutines = listOf(existing),
        )
        assertValid(result)
    }

    // ---- Editing excludes the routine's own previous version ----

    @Test
    fun `editing a routine excludes its own previously stored version from the total`() {
        // The stored version of "r1" is Mon 4h; the proposed edit is ALSO "r1", now Mon 5h.
        // Naively summing both would be 9h (invalid) -- but the stored "r1" must be excluded
        // since it's the very routine being edited, leaving only the proposed 5h.
        val storedVersion = routine(id = "r1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = "r1",
            proposedStartTime = LocalTime.of(7, 0),
            proposedEndTime = LocalTime.of(12, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(storedVersion),
        )
        assertValid(result)
    }

    @Test
    fun `editing a routine still counts every OTHER routine normally`() {
        val storedVersion = routine(id = "r1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(7, 0))
        val otherRoutine = routine(id = "r2", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(16, 0), endTime = LocalTime.of(19, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = "r1",
            proposedStartTime = LocalTime.of(7, 0),
            proposedEndTime = LocalTime.of(12, 1), // 301 min
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(storedVersion, otherRoutine),
        )
        // r2 (3h) + proposed r1 edit (301min) = 481min, over the limit.
        assertExceeds(result, DayOfWeek.MONDAY, 481)
    }

    // ---- Overlapping routines count individually (sum, not union) ----

    @Test
    fun `two routines fully overlapping in time still sum instead of taking the union`() {
        // Both routines occupy the EXACT same 3-hour window on Monday. A union-of-time-ranges
        // rule would see only 3h of wall-clock time and accept this; this validator sums each
        // routine's own configured duration instead (6h total), since each would run its own
        // separate foreground worker/network loop -- see RoutineDurationValidator's own doc.
        val existing = routine(id = "existing-1", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(10, 0))
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(7, 0),
            proposedEndTime = LocalTime.of(10, 0),
            proposedActiveDays = setOf(DayOfWeek.MONDAY),
            proposedEnabled = true,
            existingRoutines = listOf(existing),
        )
        assertExceeds(result, DayOfWeek.MONDAY, 360)
    }

    // ---- Misc ----

    @Test
    fun `a proposed routine with no active days is trivially valid`() {
        val result = RoutineDurationValidator.validate(
            proposedRoutineId = null,
            proposedStartTime = LocalTime.of(6, 0),
            proposedEndTime = LocalTime.of(13, 0),
            proposedActiveDays = emptySet(),
            proposedEnabled = true,
            existingRoutines = emptyList(),
        )
        assertValid(result)
    }

    // ---- validateSelf: the scheduling-layer convenience overload ----

    @Test
    fun `validateSelf accepts a routine whose own duration is within the limit regardless of other routines`() {
        val other = routine(id = "other", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(10, 0))
        val subject = routine(id = "subject", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(9, 0))
        // Combined with `other` this would exceed the limit, but validateSelf deliberately
        // never looks at other routines -- see its own doc.
        assertValid(RoutineDurationValidator.validateSelf(subject))
        assertTrue(other.enabled) // sanity: `other` is irrelevant to validateSelf by design
    }

    @Test
    fun `validateSelf rejects a routine that exceeds the limit entirely on its own`() {
        val subject = routine(id = "subject", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(13, 0))
        assertExceeds(RoutineDurationValidator.validateSelf(subject), DayOfWeek.MONDAY, 420)
    }

    @Test
    fun `validateSelf rejects a disabled routine whose own duration exceeds the limit`() {
        // A disabled routine can be re-enabled later without ever going through save-time
        // validation again -- validateSelf must not wave through an excessive duration merely
        // because the routine happens to be disabled right now.
        val subject = routine(id = "subject", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(6, 0), endTime = LocalTime.of(13, 0), enabled = false)
        assertExceeds(RoutineDurationValidator.validateSelf(subject), DayOfWeek.MONDAY, 420)
    }

    @Test
    fun `validateSelf accepts a routine whose own duration is exactly 300 minutes`() {
        val subject = routine(id = "subject", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0))
        assertValid(RoutineDurationValidator.validateSelf(subject))
    }

    @Test
    fun `validateSelf rejects a routine whose own duration is 301 minutes`() {
        val subject = routine(id = "subject", activeDays = setOf(DayOfWeek.MONDAY), startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 1))
        assertExceeds(RoutineDurationValidator.validateSelf(subject), DayOfWeek.MONDAY, 301)
    }
}
