package se.blick.app

import android.content.res.Configuration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.Instant

class SystemNightModeTransitionTrackerTest {

    @Test
    fun `light to dark requests a refresh`() {
        val tracker = SystemNightModeTransitionTracker(lightUiMode())

        assertTrue(tracker.record(true))
    }

    @Test
    fun `dark to light requests a refresh`() {
        val tracker = SystemNightModeTransitionTracker(darkUiMode())

        assertTrue(tracker.record(false))
    }

    @Test
    fun `light to light does not request a refresh`() {
        val tracker = SystemNightModeTransitionTracker(lightUiMode())

        assertFalse(tracker.record(false))
    }

    @Test
    fun `dark to dark does not request a refresh`() {
        val tracker = SystemNightModeTransitionTracker(darkUiMode())

        assertFalse(tracker.record(true))
    }

    @Test
    fun `unrelated configuration change does not request a refresh`() {
        val initial = lightUiMode(Configuration.UI_MODE_TYPE_NORMAL)
        val tracker = SystemNightModeTransitionTracker(initial)
        val changedDeviceType = lightUiMode(Configuration.UI_MODE_TYPE_DESK)

        assertFalse(tracker.record(systemNightModeEnabled(changedDeviceType)))
    }

    @Test
    fun `presentation refresh does not reconcile routine state`() = runTest {
        val updater = RecordingRoutineWidgetUpdater()

        refreshWidgetPresentationOnly(updater, isSystemNightMode = true)

        assertEquals(1, updater.presentationRefreshCalls)
        assertEquals(true, updater.lastSystemNightMode)
        assertEquals(0, updater.reconcileCalls)
    }

    private fun lightUiMode(type: Int = Configuration.UI_MODE_TYPE_NORMAL): Int =
        type or Configuration.UI_MODE_NIGHT_NO

    private fun darkUiMode(type: Int = Configuration.UI_MODE_TYPE_NORMAL): Int =
        type or Configuration.UI_MODE_NIGHT_YES

    private class RecordingRoutineWidgetUpdater : RoutineWidgetUpdater {
        var presentationRefreshCalls = 0
        var reconcileCalls = 0
        var lastSystemNightMode: Boolean? = null

        override suspend fun updateWithDepartures(
            routine: CommuteRoutine,
            departuresState: LiveDeparturesState,
            now: Instant,
        ) = Unit

        override suspend fun clear() = Unit

        override suspend fun reconcile() {
            reconcileCalls += 1
        }

        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit

        override suspend fun refreshPresentation(isSystemNightMode: Boolean) {
            presentationRefreshCalls += 1
            lastSystemNightMode = isSystemNightMode
        }
    }
}
