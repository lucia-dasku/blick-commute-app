package se.blick.app.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Tests [RoutineWidgetUpdater]'s own default implementation of the four-argument
 * [RoutineWidgetUpdater.updateWithDepartures] overload — added as a *new* overload (not an
 * added parameter on the existing three-argument method) specifically so implementations that
 * predate the widget's disruption strip (test fakes across several other files) don't need to
 * change at all: calling the four-argument overload on any implementation that only overrides
 * the three-argument one must silently forward to it, ignoring the disruption. Only
 * [GlanceRoutineWidgetUpdater] (exercised indirectly via `RoutineActiveWindowWorkerTest`'s own
 * `RecordingWidgetUpdater`, which DOES override both) actually renders a disruption.
 */
class RoutineWidgetUpdaterTest {

    private fun routine() = CommuteRoutine(
        id = "r1",
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = null,
        lineDesignation = "14",
        directionCode = null,
        destinationLabel = "Fruängen",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun disruption() = Disruption(
        disruptionId = "d1",
        version = 1,
        createdAt = Instant.EPOCH,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage("Delays on line 14", "Details", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    /** Implements ONLY the pre-existing three-argument method — exactly what every
     * `RoutineWidgetUpdater` fake elsewhere in this codebase looked like before the disruption
     * strip existed, and still looks like in every file that has no reason to care about it. */
    private class ThreeArgumentOnlyFake : RoutineWidgetUpdater {
        var lastRoutine: CommuteRoutine? = null
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            lastRoutine = routine
        }
        override suspend fun clear() = Unit
        override suspend fun reconcile() = Unit
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    @Test
    fun `calling the four-argument overload on a fake that only overrides three arguments still forwards, ignoring the disruption`() =
        runTest {
            val fake = ThreeArgumentOnlyFake()
            val routine = routine()

            fake.updateWithDepartures(routine, LiveDeparturesState.Loading, Instant.EPOCH, disruption())

            assertEquals(routine, fake.lastRoutine)
        }
}
