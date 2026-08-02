package se.blick.app.widget

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import java.time.Instant

/**
 * Exercises [WidgetReconcileWorker.doWork] directly via WorkManager's own
 * [TestListenableWorkerBuilder] with a fake [RoutineWidgetUpdater] — the same pattern
 * `RoutineActiveWindowWorkerTest` uses for its own [androidx.hilt.work.HiltWorker]. Covers the
 * one thing this worker exists to guarantee: [RoutineWidgetUpdater.reconcile] actually runs and
 * the worker reports success, replacing the untracked `CoroutineScope(...).launch { }`
 * [BlickRoutineWidgetReceiver.onUpdate] used before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetReconcileWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {}
        override suspend fun clear() {}
        override suspend fun reconcile() {
            reconcileCallCount++
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) {}
    }

    private fun buildWorker(widgetUpdater: RoutineWidgetUpdater): WidgetReconcileWorker =
        TestListenableWorkerBuilder<WidgetReconcileWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = WidgetReconcileWorker(appContext, workerParameters, widgetUpdater)
            })
            .build()

    @Test
    fun `doWork calls reconcile exactly once and returns success`() = runTest {
        val widgetUpdater = RecordingWidgetUpdater()
        val worker = buildWorker(widgetUpdater)

        val result = worker.doWork()

        assertEquals(1, widgetUpdater.reconcileCallCount)
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
