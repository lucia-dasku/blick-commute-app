package se.blick.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val LOG_TAG = "BootCompletedReceiver"

/**
 * Re-schedules every enabled routine's next active-window activation as soon as the device
 * finishes booting, without waiting for the app to be opened first.
 *
 * [RoutineScheduleReconciler.reconcileAll] already runs on ordinary process start (see
 * `BlickApplication.onCreate`) and WorkManager's own persistence already survives a reboot on
 * its own in most cases (see [RoutineScheduleReconciler]'s class doc) — this receiver is the
 * backstop for the one case neither of those covers: a reboot after which the app process
 * never happens to start on its own before a routine's next window would have opened.
 *
 * `@AndroidEntryPoint` lets this manifest-registered receiver field-inject
 * [RoutineScheduleReconciler] like every other class in this codebase. `goAsync()` plus a
 * plain background coroutine is required because `onReceive` itself must return promptly, but
 * [RoutineScheduleReconciler.reconcileAll] is a suspend function that reads from Room and calls
 * WorkManager once per enabled routine.
 *
 * An ordinary exception from [RoutineScheduleReconciler.reconcileAll] (a Room read failure, a
 * WorkManager scheduling call throwing on some OEM, ...) is caught and logged rather than left
 * to crash this process — the same [CancellationException]-vs-everything-else split used by
 * every other background entry point here ([se.blick.app.widget.WidgetReconcileWorker],
 * [RoutineActiveWindowWorker]). Uniquely important on THIS receiver specifically: it runs
 * during `ACTION_BOOT_COMPLETED` handling, where an uncaught exception would crash the app
 * immediately after every future boot too if the underlying failure persists (e.g. corrupted
 * routine data), not just this one time — a boot-crash loop, rather than an ordinary single
 * crash a user could work around by reopening the app.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var routineScheduleReconciler: RoutineScheduleReconciler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                routineScheduleReconciler.reconcileAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Post-boot routine reconcile failed; leaving existing WorkManager scheduling in place", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
