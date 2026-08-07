package se.blick.app.notification

import android.util.Log
import kotlinx.coroutines.CancellationException
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "StopRoutineNotification"

/**
 * The effect of tapping the ongoing notification's Stop action (the "Stop/Unpin" control the
 * Live Update spec requires) — kept as its own plain, Hilt-injected class, separate from
 * [StopRoutineNotificationReceiver], so this logic is unit-testable with fakes rather than only
 * reachable through a manifest-registered [android.content.BroadcastReceiver] (see
 * [se.blick.app.scheduling.NotificationRecoveryCoordinator] for the same split — a plain
 * injectable class doing the real work, invoked by `BlickApplication`'s thin
 * `Intent.ACTION_TIMEZONE_CHANGED` receiver).
 *
 * Stopping today's active window early is given exactly the same effect as the existing
 * "pause for today" control on the routine details screen (`RoutineDetailsViewModel.pauseToday`)
 * — writing [se.blick.app.domain.model.CommuteRoutine.pausedDate] to today's date — rather than
 * inventing a separate mechanism. [RoutineActiveWindowWorker][se.blick.app.scheduling.RoutineActiveWindowWorker]
 * already re-reads the routine on every ~30-second loop tick and breaks out once
 * `pausedDate == today`, so this alone stops that loop (and, via its own `finally`, clears the
 * notification and the widget) within one tick — this holds regardless of whether
 * [routineScheduler] below succeeds, since that call only affects the FUTURE occurrence's
 * scheduling, never whether the worker's own already-running loop notices the pause. [RoutineNotifier.remove]
 * and [RoutineWidgetUpdater.reconcile] are still called directly here too, so the notification and
 * the widget both update immediately on tap rather than up to 30 seconds later.
 *
 * "Today" is deliberately resolved from [clock] combined with [deviceZoneProvider]'s CURRENT
 * zone (mirroring the worker's own `zonedNow()`), never a zone-less `LocalDate.now(clock)` —
 * using the worker's own device-zone definition of "today" is what guarantees this write and the
 * worker's own read of it agree, even right around local midnight.
 *
 * **Persistence succeeding and scheduling succeeding are two different results** (the same
 * split every other routine mutation in this codebase now makes — see
 * `RoutineDetailsViewModel.pauseToday`'s own doc). Once [routineRepository.pauseForDate] accepts
 * the write, the routine genuinely IS paused for today: [routineNotifier.remove] runs
 * unconditionally from that point on, even if the immediately-following
 * [routineScheduler.scheduleActivation] call — which only reschedules the NEXT, future
 * occurrence, not today's already-open window — throws. Conversely, if
 * [routineRepository.getById] or [routineRepository.pauseForDate] itself fails, this returns
 * without touching the notification at all: an active notification must never be removed on the
 * strength of a pause that may not have actually happened. Every step below is individually
 * wrapped so an ordinary Room/scheduler exception is always contained here — never left to
 * escape into [StopRoutineNotificationReceiver]'s own detached coroutine, which has no default
 * exception handler and would otherwise crash the app — while a genuine
 * [CancellationException] is always rethrown unconverted.
 */
@Singleton
class StopRoutineNotificationAction @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineNotifier: RoutineNotifier,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) {
    suspend fun stop(routineId: String) {
        val routine = try {
            routineRepository.getById(routineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to read routine $routineId while handling its Stop action", e)
            return
        }
        if (routine != null) {
            val today = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone()).toLocalDate()
            try {
                routineRepository.pauseForDate(routineId, today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to pause routine $routineId for today while handling its Stop action", e)
                // The pause did NOT happen -- do not pretend it did, and do not remove a
                // notification that may still correctly represent an active window.
                return
            }
            // Room succeeded -- the routine IS paused for today now, regardless of whether the
            // reschedule below succeeds; see the class doc.
            try {
                routineScheduler.scheduleActivation(routine.copy(pausedDate = today))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Routine $routineId was paused for today but rescheduling it failed", e)
            }
        }
        routineNotifier.remove()
        // reconcile(), not clear(): correctly handles both "routine existed and just got
        // paused" and "routine was already deleted out from under this action" from scratch,
        // without an extra null-check branch here (see RoutineWidgetUpdater.reconcile's own doc).
        // Best-effort: a widget/Glance/DataStore failure here must never propagate out of stop()
        // -- the pause/notification-removal above have already genuinely succeeded by this
        // point, and StopRoutineNotificationReceiver's own detached coroutine scope has no
        // default exception handler, so an uncaught exception here would crash the app (see
        // runWidgetUpdateSafely's own doc).
        runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
    }
}
