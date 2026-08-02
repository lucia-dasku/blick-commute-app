package se.blick.app.widget

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * things this worker exists to guarantee: [RoutineWidgetUpdater.reconcile] actually runs and the
 * worker reports success (replacing the untracked `CoroutineScope(...).launch { }`
 * [BlickRoutineWidgetReceiver.onUpdate] used before), an ordinary failure asks WorkManager for a
 * retry rather than silently giving up, and a genuine [CancellationException] is never converted
 * into a retry.
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

    private class FailingWidgetUpdater(private val error: Throwable) : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {}
        override suspend fun clear() {}
        override suspend fun reconcile() {
            reconcileCallCount++
            throw error
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

    @Test
    fun `an ordinary reconcile failure returns retry, not success or a thrown exception`() = runTest {
        val widgetUpdater = FailingWidgetUpdater(RuntimeException("widget update failed"))
        val worker = buildWorker(widgetUpdater)

        val result = worker.doWork()

        assertEquals(1, widgetUpdater.reconcileCallCount)
        assertTrue(
            "expected Result.retry(), got $result",
            result is ListenableWorker.Result.Retry,
        )
    }

    @Test
    fun `a genuine CancellationException is rethrown, never converted into a retry`() = runTest {
        val widgetUpdater = FailingWidgetUpdater(CancellationException("test cancellation"))
        val worker = buildWorker(widgetUpdater)

        try {
            worker.doWork()
            fail("expected the CancellationException to propagate, not be swallowed into a Result")
        } catch (e: CancellationException) {
            // Expected -- doWork() itself does not catch a real CancellationException.
        }
    }
}
