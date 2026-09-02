package se.blick.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration as AndroidConfiguration
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
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
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
 * cold-start reconciliation, timezone-change reconciliation, AND notification-availability
 * recovery — [onCreate] calls [NotificationRecoveryCoordinator.onAppStart] once, the
 * `ProcessLifecycleOwner` `ON_START` observer calls [NotificationRecoveryCoordinator.onForeground]
 * every time the app returns to the foreground, and the [Intent.ACTION_TIMEZONE_CHANGED] receiver
 * below calls [NotificationRecoveryCoordinator.onTimeZoneChanged]. No call site here launches
 * [se.blick.app.scheduling.RoutineScheduleReconciler.reconcileAll] directly any more — that used to run alongside the
 * foreground trigger with no shared coordination between the two, and the foreground trigger's own
 * unconditional reconciliation was a real regression in its own right (its
 * `ExistingWorkPolicy.REPLACE` could cancel and replace an already-`RUNNING`
 * [se.blick.app.scheduling.RoutineActiveWindowWorker] merely because the user opened the app);
 * the timezone receiver had the exact same uncoordinated-direct-call problem until it was routed
 * through the coordinator too. Routing all three triggers through one coordinator, serialized by
 * its own `Mutex`, is what makes "cold start and the first foreground overlapping" (which
 * genuinely happens — `ON_START` fires once at process start too) — and a timezone change landing
 * mid-recovery — safe: whichever call runs second simply observes whatever the first one already
 * left in place, or (for a genuine timezone change specifically) still correctly replaces it,
 * rather than racing it uncoordinated. There is deliberately no separate boot-specific receiver
 * here either any more — see [NotificationRecoveryCoordinator.onAppStart]'s own doc for why a
 * dedicated `BootCompletedReceiver` turned out to be redundant with, and unsafe alongside, this
 * same [onCreate] call.
 */
@HiltAndroidApp
class BlickApplication : Application(), Configuration.Provider {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    @Inject lateinit var notificationRecoveryCoordinator: NotificationRecoveryCoordinator
    @Inject lateinit var premiumEntitlementRepository: PremiumEntitlementRepository
    @Inject lateinit var routineWidgetUpdater: RoutineWidgetUpdater

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var systemNightModeTransitionTracker: SystemNightModeTransitionTracker

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()
        systemNightModeTransitionTracker = SystemNightModeTransitionTracker(resources.configuration.uiMode)
        applicationScope.launch {
            refreshPremiumBeforeRecovery(premiumEntitlementRepository) {
                notificationRecoveryCoordinator.onAppStart()
            }
        }
        registerTimeZoneChangeReceiver()
        registerForegroundRecovery()
    }

    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        super.onConfigurationChanged(newConfig)
        refreshWidgetPresentationIfSystemNightModeChanged(systemNightModeEnabled(newConfig.uiMode))
    }

    private fun registerForegroundRecovery() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    refreshWidgetPresentationIfSystemNightModeChanged(
                        systemNightModeEnabled(resources.configuration.uiMode),
                    )
                    applicationScope.launch {
                        refreshPremiumBeforeRecovery(premiumEntitlementRepository) {
                            notificationRecoveryCoordinator.onForeground()
                        }
                    }
                }
            },
        )
    }

    private fun refreshWidgetPresentationIfSystemNightModeChanged(isNightMode: Boolean) {
        if (!systemNightModeTransitionTracker.record(isNightMode)) return
        applicationScope.launch {
            refreshWidgetPresentationOnly(routineWidgetUpdater, isNightMode)
        }
    }

    private fun registerTimeZoneChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applicationScope.launch { notificationRecoveryCoordinator.onTimeZoneChanged() }
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

/** Tracks only the night-mode bits, ignoring unrelated configuration changes. */
internal class SystemNightModeTransitionTracker(initialUiMode: Int) {
    private var lastKnownNightMode = systemNightModeEnabled(initialUiMode)

    @Synchronized
    fun record(isNightMode: Boolean): Boolean {
        if (isNightMode == lastKnownNightMode) return false
        lastKnownNightMode = isNightMode
        return true
    }
}

internal fun systemNightModeEnabled(uiMode: Int): Boolean =
    (uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK) == AndroidConfiguration.UI_MODE_NIGHT_YES

/** Performs the presentation-only widget redraw without allowing a best-effort failure to escape. */
internal suspend fun refreshWidgetPresentationOnly(
    routineWidgetUpdater: RoutineWidgetUpdater,
    isSystemNightMode: Boolean,
) {
    runWidgetUpdateSafely { routineWidgetUpdater.refreshPresentation(isSystemNightMode) }
}

/** Refreshes entitlement to a fresh or cache-aware fail-soft state before tier-sensitive recovery. */
internal suspend fun refreshPremiumBeforeRecovery(
    premiumEntitlementRepository: PremiumEntitlementRepository,
    recover: suspend () -> Unit,
) {
    premiumEntitlementRepository.refresh()
    recover()
}
