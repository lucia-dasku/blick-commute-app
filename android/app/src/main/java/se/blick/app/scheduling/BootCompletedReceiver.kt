package se.blick.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
