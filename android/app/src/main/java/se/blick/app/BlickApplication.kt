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
import se.blick.app.scheduling.NotificationRecoveryCoordinator
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
 * [NotificationRecoveryCoordinator] (see that class's own doc) is now the SOLE authority for
 * both cold-start reconciliation and notification-availability recovery — [onCreate] calls
 * [NotificationRecoveryCoordinator.onAppStart] once, and the `ProcessLifecycleOwner` `ON_START`
 * observer calls [NotificationRecoveryCoordinator.onForeground] every time the app returns to
 * the foreground. Neither call site launches [RoutineScheduleReconciler.reconcileAll]
 * independently any more — that used to run alongside the foreground trigger with no shared
 * coordination between the two, and the foreground trigger's own unconditional reconciliation
 * was a real regression in its own right (its `ExistingWorkPolicy.REPLACE` could cancel and
 * replace an already-`RUNNING` [se.blick.app.scheduling.RoutineActiveWindowWorker] merely
 * because the user opened the app). Routing both triggers through one coordinator, serialized
 * by its own `Mutex`, is what makes "cold start and the first foreground overlapping" (which
 * genuinely happens — `ON_START` fires once at process start too) safe: whichever call runs
 * second simply observes whatever the first one already left in place rather than racing it.
 *
 * [RoutineScheduleReconciler.reconcileAll] is still used directly, unchanged, by the
 * [Intent.ACTION_TIMEZONE_CHANGED] receiver below — a live device timezone change that an
 * already-enqueued `WorkRequest`'s fixed `initialDelay` cannot pick up on its own (see
 * [RoutineScheduleReconciler]'s own doc) is a distinct trigger from "this process just started"
 * or "notifications became available again", so it is out of [NotificationRecoveryCoordinator]'s
 * scope by design — as is [se.blick.app.scheduling.BootCompletedReceiver], which also still
 * calls [RoutineScheduleReconciler.reconcileAll] directly for the same reason.
 */
@HiltAndroidApp
class BlickApplication : Application(), Configuration.Provider {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    @Inject lateinit var routineScheduleReconciler: RoutineScheduleReconciler

    @Inject lateinit var notificationRecoveryCoordinator: NotificationRecoveryCoordinator

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { notificationRecoveryCoordinator.onAppStart() }
        registerTimeZoneChangeReceiver()
        registerForegroundRecovery()
    }

    private fun registerForegroundRecovery() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { notificationRecoveryCoordinator.onForeground() }
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
