package se.blick.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import se.blick.app.scheduling.RoutineScheduleReconciler
import javax.inject.Inject

/**
 * [Configuration.Provider] supplies [HiltWorkerFactory] to WorkManager so
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] can constructor-inject its dependencies
 * like every other class in this codebase, rather than through a manual service locator.
 * `AndroidManifest.xml` disables WorkManager's own default `androidx.startup` initializer (see
 * its `WorkManagerInitializer` `tools:node="remove"` entry) — without that, WorkManager would
 * initialize itself with the default configuration before this class's [workManagerConfiguration]
 * ever runs, crashing with "WorkManager is already initialized" the first time on-demand
 * initialization is attempted here instead.
 *
 * Runs [RoutineScheduleReconciler.reconcileAll] (see that class's own doc) twice over: once on
 * process start (covering reboot, an app update, and ordinary process recreation — WorkManager
 * itself already persists enqueued work across all of these; this is a defensive backstop, not
 * the primary scheduling mechanism, since saving/editing/enabling/disabling/pausing/resuming a
 * routine already calls the scheduler directly at the point of change), and again every time
 * [Intent.ACTION_TIMEZONE_CHANGED] is broadcast while this process stays alive — a live device
 * timezone change that an already-enqueued `WorkRequest`'s fixed `initialDelay` cannot pick up
 * on its own (see [RoutineScheduleReconciler]'s doc). No `BOOT_COMPLETED` receiver is used —
 * starting a `dataSync` foreground service directly from one is restricted on modern Android;
 * WorkManager's own persistence plus this process-start reconciliation covers reboot instead.
 */
@HiltAndroidApp
class BlickApplication : Application(), Configuration.Provider {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    @Inject lateinit var routineScheduleReconciler: RoutineScheduleReconciler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { routineScheduleReconciler.reconcileAll() }
        registerTimeZoneChangeReceiver()
    }

    private fun registerTimeZoneChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applicationScope.launch { routineScheduleReconciler.reconcileAll() }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
