package se.blick.app.widget

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

private const val LOG_TAG = "WidgetReconcileWorker"

/**
 * Runs [RoutineWidgetUpdater.reconcile] via WorkManager rather than a raw, untracked coroutine —
 * see [enqueue] for the one call site ([BlickRoutineWidgetReceiver.onUpdate]) this replaces.
 * WorkManager persists its own queue to disk and survives process death, so this reconcile is
 * guaranteed to eventually run even if the process is killed moments after
 * [BlickRoutineWidgetReceiver.onUpdate] returns — the same reliability guarantee every other
 * scheduled unit of work in this app already gets (see [se.blick.app.scheduling.RoutineActiveWindowWorker]/
 * [se.blick.app.scheduling.WorkManagerRoutineScheduler]), rather than a special-cased
 * best-effort exception for this one call site.
 *
 * [ExistingWorkPolicy.REPLACE] against the single, fixed [UNIQUE_WORK_NAME] means a burst of
 * `APPWIDGET_UPDATE` deliveries for several widget ids at once (e.g. after a reboot, or several
 * instances placed in quick succession)
 * collapses to at most one pending reconcile rather than enqueuing one per id — safe because
 * [RoutineWidgetUpdater.reconcile] is idempotent and always recomputes the current state from
 * scratch, so only the most recently enqueued attempt ever needs to actually run.
 *
 * [doWork] is deliberately the ONE `RoutineWidgetUpdater` call site in this codebase that does
 * NOT wrap with [runWidgetUpdateSafely] — that helper exists to stop a widget failure from
 * derailing some OTHER, already-successful operation (a posted notification, a saved routine,
 * ...); this worker's entire job IS the widget call, so silently swallowing its failure would
 * mean a freshly-placed widget instance (or one that missed an update) could simply never
 * self-correct, with nothing left to retry it. Instead, an ordinary exception here returns
 * [androidx.work.ListenableWorker.Result.retry] — WorkManager only retries a unit of work when
 * it is told to, via this exact return value (see the official guidance in
 * [WorkRequest documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)) —
 * so a transient Glance/DataStore failure gets WorkManager's own exponential backoff and another
 * attempt, rather than silently giving up after one failed try. A genuine [CancellationException]
 * (this worker's own unique work was replaced or cancelled — e.g. [enqueue] running again)
 * always rethrows unconverted, exactly like every other coroutine-cancellation handling
 * elsewhere in this codebase — it is not a reconcile failure to retry.
 */
@HiltWorker
class WidgetReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        routineWidgetUpdater.reconcile()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Widget reconcile failed; requesting a WorkManager retry", e)
        Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "widget-reconcile"

        /** Enqueues a one-time, unique reconcile — see this class's own doc. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetReconcileWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
