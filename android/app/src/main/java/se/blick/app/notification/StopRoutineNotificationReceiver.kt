package se.blick.app.notification

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
 * Receives the Stop action's broadcast from the ongoing notification (see
 * [RoutineNotificationBuilder]'s Stop action) and hands off to [StopRoutineNotificationAction].
 * Only ever triggered by this app's own explicit [android.app.PendingIntent] — never a system or
 * cross-app broadcast — so this is registered `exported="false"` in the manifest, unlike a
 * receiver for a genuine protected system broadcast, which must be exported to receive it at all.
 *
 * `@AndroidEntryPoint` lets this manifest-registered receiver field-inject
 * [StopRoutineNotificationAction] like every other class in this codebase. `goAsync()` plus a
 * plain background coroutine is required because `onReceive` itself must return promptly, but
 * [StopRoutineNotificationAction.stop] is a suspend function that reads from and writes to Room.
 */
@AndroidEntryPoint
class StopRoutineNotificationReceiver : BroadcastReceiver() {

    @Inject lateinit var stopRoutineNotificationAction: StopRoutineNotificationAction

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra(RoutineNotificationIds.EXTRA_ROUTINE_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                stopRoutineNotificationAction.stop(routineId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
