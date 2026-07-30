package se.blick.app.scheduling

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.departureIdentity
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.RoutineNotificationBuilder
import se.blick.app.notification.RoutineNotificationIds
import se.blick.app.notification.RoutineNotificationMapper
import se.blick.app.notification.RoutineNotifier
import java.time.Clock
import java.time.ZonedDateTime

/** The interval between departure re-fetches while a routine's active window is running (the
 * "30-second active-window loop" from the product doc). Not a [androidx.work.PeriodicWorkRequest]
 * — WorkManager's periodic-work minimum interval (15 minutes) cannot represent this; see
 * [WorkManagerRoutineScheduler]'s class doc. */
internal const val ACTIVE_WINDOW_REFRESH_INTERVAL_MS = 30_000L

/**
 * Runs one routine's active window end to end: checks notification availability, enters
 * foreground execution immediately with a valid notification, fetches and shows departures
 * right away, then re-fetches and silently updates the SAME notification (stable
 * [RoutineNotificationIds.NOTIFICATION_ID], `setOnlyAlertOnce`, no repeated sound/vibration/
 * heads-up — see [RoutineNotificationBuilder]) roughly every [ACTIVE_WINDOW_REFRESH_INTERVAL_MS]
 * until the routine's configured end time, then removes the notification and reschedules the
 * next eligible occurrence via [RoutineScheduler].
 *
 * A single [androidx.work.OneTimeWorkRequest] (see [WorkManagerRoutineScheduler]) rather than a
 * periodic one — WorkManager cannot repeat more often than 15 minutes, far too coarse for this
 * 30-second refresh, so that refresh is this plain in-`doWork` `delay()` loop instead, wrapped
 * by one long-running foreground execution for the whole window (see the official
 * "Support for long-running workers" guide this design follows:
 * developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).
 *
 * Handles a late start (WorkManager/JobScheduler deferred this work past its intended start
 * time) by re-checking [NextOccurrenceCalculator] itself: if the window has already fully
 * elapsed by the time this actually runs, this occurrence is skipped entirely (no foreground
 * execution, no notification) and the next eligible occurrence is scheduled instead — never
 * silently "catches up" by running a window that's already over. `now` is always re-derived
 * from [clock]'s instant combined with [deviceZoneProvider]'s CURRENT zone (never
 * `ZonedDateTime.now(clock)`, which would resolve against `clock`'s own zone —
 * `Clock.systemUTC()` in production, see `di/TimeModule.kt`) so a device timezone change is
 * always reflected, and DST is handled correctly by the JDK (see [NextOccurrenceCalculator]'s
 * own doc).
 *
 * [NotificationAvailabilityChecker] is consulted BEFORE any foreground execution begins (see
 * that interface's doc — this is the one shared source of truth also used by
 * [se.blick.app.notification.AndroidRoutineNotifier] and the routine details screen): if
 * notifications are unavailable (permission missing, app-wide toggle off, or the Blick channel
 * specifically disabled), this worker never calls [setForeground], never builds or touches a
 * notification or channel, and never claims delivery is active — it simply reschedules the
 * next eligible occurrence and exits. [setForeground] is only ever called once this worker is
 * actually about to run its loop.
 *
 * Also re-checks the routine's existence/enabled/paused-for-today state on every single loop
 * tick (not just once at the start) — an edit, disable, pause, or delete that happens while this
 * worker is already running takes effect on its very next tick, stopping the loop, removing the
 * notification, and (if the routine still exists) rescheduling its next eligible occurrence.
 *
 * Terminal handling distinguishes three cases so the next occurrence is never silently lost and
 * a real coroutine cancellation is never mistaken for a handled failure: a genuine
 * [CancellationException] (the worker itself was cancelled — e.g. WorkManager replacing this
 * routine's unique work because it was just edited/disabled/deleted elsewhere) is always
 * rethrown unconverted, WITHOUT rescheduling — rescheduling here would resurrect obsolete work
 * that whatever triggered the cancellation may already be in the middle of replacing or
 * cancelling outright. Any other unexpected exception (a failure entering foreground execution,
 * or anything else unhandled mid-loop) is caught, treated as a normal end of this run, and still
 * proceeds to clean up the notification and reschedule the next eligible occurrence — the same
 * as a normal window-end completion. [routineNotifier.remove] always runs in a `finally` once
 * foreground execution actually started, whichever of these paths is taken.
 *
 * Declares the `dataSync` foreground service type (see `AndroidManifest.xml`'s
 * `SystemForegroundService` override and the `FOREGROUND_SERVICE_DATA_SYNC` permission) — the
 * closest official category to "sync fresh departure data from the Blick backend while
 * visible to the user as an ongoing notification." Android 15+ limits total `dataSync`
 * foreground-service time to six hours per rolling 24 hours app-wide, which is exactly why this
 * worker only ever runs for one configured active window and always stops itself at that
 * window's end rather than running continuously.
 */
@HiltWorker
class RoutineActiveWindowWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val routineRepository: RoutineRepository,
    private val getLiveDepartures: GetLiveDeparturesUseCase,
    private val staleSnapshotRepository: StaleSnapshotRepository,
    private val routineNotifier: RoutineNotifier,
    private val routineNotificationBuilder: RoutineNotificationBuilder,
    private val routineScheduler: RoutineScheduler,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) : CoroutineWorker(context, params) {

    private fun zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())

    override suspend fun doWork(): Result {
        val routineId = inputData.getString(KEY_ROUTINE_ID) ?: return Result.failure()
        val routine = routineRepository.getById(routineId) ?: return Result.success()
        if (!routine.enabled) return Result.success()

        val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, zonedNow(), excludedDate = routine.pausedDate)
        if (occurrence !is NextOccurrence.ActiveNow) {
            // Either started late (the intended window already fully elapsed) or was
            // somehow enqueued before its window opened -- either way, this specific
            // occurrence is no longer valid to run. Reschedule the next eligible one instead
            // of running (or partially running) a stale window. No foreground execution, no
            // notification, ever entered for this case.
            rescheduleNext(routineId)
            return Result.success()
        }
        val windowEnd = occurrence.windowEnd

        // Checked BEFORE any foreground execution or notification/channel construction begins
        // (see class doc) -- if unavailable, this occurrence is skipped exactly like a late
        // start above: reschedule the next eligible one and exit, without ever calling
        // setForeground, building a notification, or touching the channel.
        if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) {
            rescheduleNext(routineId)
            return Result.success()
        }

        var enteredForeground = false

        try {
            setForeground(createForegroundInfo(routine))
            enteredForeground = true

            while (true) {
                val current = routineRepository.getById(routineId) ?: break
                if (!current.enabled) break
                if (current.pausedDate == zonedNow().toLocalDate()) break
                if (!zonedNow().isBefore(windowEnd)) break

                val identity = current.departureIdentity()
                val previous = staleSnapshotRepository.get(routineId, identity)
                val departuresState = getLiveDepartures(current, previous = previous).last()
                if (departuresState is LiveDeparturesState.Live) {
                    staleSnapshotRepository.save(routineId, identity, departuresState.snapshot)
                }

                val model = RoutineNotificationMapper.map(current, departuresState, clock.instant())
                // The real NotificationPostResult is intentionally not surfaced anywhere from
                // here (there is no UI attached to a background worker to report it to) --
                // but it is also never used to claim success; showOrUpdate itself already
                // fails safely (see AndroidRoutineNotifier) if app notifications or the Blick
                // channel are disabled, which this worker relies on rather than duplicating.
                routineNotifier.showOrUpdate(model)

                delay(ACTIVE_WINDOW_REFRESH_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // A real coroutine cancellation (this worker's own work was replaced or cancelled
            // elsewhere, e.g. an edit/disable/delete enqueuing newer unique work for the same
            // routine id) -- must propagate unconverted, and must NOT reschedule: whatever
            // cancelled this run may already be enqueuing (or intentionally not enqueuing) its
            // own replacement, and resurrecting another scheduled occurrence here could leave
            // obsolete work behind. The `finally` below still cleans up the notification.
            throw e
        } catch (e: Exception) {
            // Any other unexpected failure (e.g. setForeground() itself throwing on this
            // device/OEM, or an unhandled error mid-loop) -- treated as a normal end of this
            // run rather than an unhandled crash with no next occurrence ever scheduled. Falls
            // through to the reschedule call below, same as a normal window-end completion.
        } finally {
            if (enteredForeground) routineNotifier.remove()
        }

        rescheduleNext(routineId)
        return Result.success()
    }

    /** Re-reads the routine from storage before rescheduling — it may have been edited (e.g. a
     * new end time) since it was first read in [doWork], or deleted mid-run, in which case
     * there is nothing left to schedule and this is correctly a no-op. Never called on a real
     * [CancellationException] path (see [doWork]'s doc) so a cancellation caused by an
     * edit/delete/unique-work replacement elsewhere can never have its intended effect undone
     * by this worker enqueuing obsolete work again. */
    private suspend fun rescheduleNext(routineId: String) {
        routineRepository.getById(routineId)?.let { latest -> routineScheduler.scheduleActivation(latest) }
    }

    private fun createForegroundInfo(routine: CommuteRoutine): ForegroundInfo {
        val loadingModel = RoutineNotificationMapper.map(routine, LiveDeparturesState.Loading, clock.instant())
        val notification = routineNotificationBuilder.build(loadingModel)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(RoutineNotificationIds.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(RoutineNotificationIds.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_ROUTINE_ID = "routineId"
    }
}
