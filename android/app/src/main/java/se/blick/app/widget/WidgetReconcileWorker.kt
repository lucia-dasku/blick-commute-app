package se.blick.app.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
 */
@HiltWorker
class WidgetReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        routineWidgetUpdater.reconcile()
        return Result.success()
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
