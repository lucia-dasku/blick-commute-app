package se.blick.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import se.blick.app.scheduling.ForegroundNotificationRecovery
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
 * Runs [RoutineScheduleReconciler.reconcileAll] (see that class's own doc) on two triggers,
 * both cheap and idempotent to repeat: once on process start (covering reboot — see
 * [se.blick.app.scheduling.BootCompletedReceiver] for the dedicated `BOOT_COMPLETED` receiver
 * that also calls it — plus an app update and ordinary process recreation; WorkManager itself
 * already persists enqueued work across all of these, so this is a defensive backstop, not the
 * primary scheduling mechanism, since saving/editing/enabling/disabling/pausing/resuming a
 * routine already calls the scheduler directly at the point of change); and every time
 * [Intent.ACTION_TIMEZONE_CHANGED] is broadcast while this process stays alive — a live device
 * timezone change that an already-enqueued `WorkRequest`'s fixed `initialDelay` cannot pick up
 * on its own (see [RoutineScheduleReconciler]'s doc).
 *
 * Every time the app returns to the foreground ([ProcessLifecycleOwner]'s `ON_START`), this runs
 * [ForegroundNotificationRecovery.onForeground] instead — see that class's own doc for why:
 * calling [RoutineScheduleReconciler.reconcileAll] on literally every foreground was a real
 * regression (its `ExistingWorkPolicy.REPLACE` could cancel and replace an already-`RUNNING`
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] merely because the user opened the app).
 * [ForegroundNotificationRecovery] instead only acts on a genuine notification-availability
 * transition, and even then only touches routines whose active window is open right now and
 * have no worker already running for it. `ON_START` fires once at process start too (before
 * this class's own [RoutineScheduleReconciler.reconcileAll] call even runs), so the two
 * triggers overlap on cold start — harmless, since [ForegroundNotificationRecovery.onForeground]
 * only ever acts on a transition, and there is nothing to transition from on the very first
 * check of a fresh process.
 */
@HiltAndroidApp
class BlickApplication : Application(), Configuration.Provider {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    @Inject lateinit var routineScheduleReconciler: RoutineScheduleReconciler

    @Inject lateinit var foregroundNotificationRecovery: ForegroundNotificationRecovery

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { routineScheduleReconciler.reconcileAll() }
        registerTimeZoneChangeReceiver()
        registerForegroundRecovery()
    }

    private fun registerForegroundRecovery() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { foregroundNotificationRecovery.onForeground() }
                }
            },
        )
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
