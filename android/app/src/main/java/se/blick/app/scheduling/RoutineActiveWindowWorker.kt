package se.blick.app.scheduling

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.blick.app.data.repository.RoutineOccurrenceRuntimeRepository
import se.blick.app.data.repository.RoutineOccurrenceRuntimeState
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.RoutineWorkOwnershipRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.GetDisruptionsUseCase
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.RoutineDurationValidationResult
import se.blick.app.domain.usecase.RoutineDurationValidator
import se.blick.app.domain.usecase.departureIdentity
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.RoutineNotificationBuilder
import se.blick.app.notification.RoutineNotificationIds
import se.blick.app.notification.RoutineNotificationMapper
import se.blick.app.notification.RoutineNotifier
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

private const val LOG_TAG = "RoutineActiveWindowWorker"

/** The interval between departure re-fetches while a routine's active window is running (the
 * "30-second active-window loop" from the product doc). Not a [androidx.work.PeriodicWorkRequest]
 * — WorkManager's periodic-work minimum interval (15 minutes) cannot represent this; see
 * [WorkManagerRoutineScheduler]'s class doc. */
internal const val ACTIVE_WINDOW_REFRESH_INTERVAL_MS = 30_000L

/** How long each loop tick will wait for a disruptions fetch before giving up on it for THIS
 * tick — see the main loop's own comment on why this exists: SL Deviations must never be able
 * to delay, let alone indefinitely hang, the departures notification, which is always posted
 * first and does not wait on this at all. Comfortably shorter than
 * [ACTIVE_WINDOW_REFRESH_INTERVAL_MS] so a slow request can never itself become the bottleneck
 * that eats into the next tick — and however much of it a slow-but-successful fetch actually
 * uses is subtracted from that same tick's own delay (see the main loop's own comment), so tick
 * spacing stays close to [ACTIVE_WINDOW_REFRESH_INTERVAL_MS] rather than drifting up to this
 * much beyond it on every slow tick. */
internal const val DISRUPTIONS_FETCH_TIMEOUT_MS = 5_000L

/** Hard, real-elapsed-time safety backstop for this worker's own foreground execution —
 * independent of a routine's configured [ACTIVE_WINDOW_REFRESH_INTERVAL_MS]-ticked `windowEnd`,
 * which is derived from local clock-face start/end times and can diverge from real elapsed time
 * on a daylight-saving-time transition ([se.blick.app.domain.usecase.RoutineDurationValidator]'s
 * create/edit validation has no date/zone to see this with): the "clocks repeat one hour"
 * autumn transition means a nominally [se.blick.app.domain.usecase.MAX_DAILY_ACTIVE_MINUTES]-long
 * configured window can genuinely run up to an hour longer in real time than its clock-face
 * duration suggests. 330 minutes (5h30m) sits comfortably under Android's actual six-hour
 * `dataSync` foreground-service limit even in that worst case, so this worker always stops
 * itself well before the platform would — regardless of why real elapsed time might exceed
 * what was expected; DST is the one known cause, but this is a general safety net, not a
 * DST-specific special case.
 *
 * Measured via [ElapsedRealtimeProvider] (backed by [android.os.SystemClock.elapsedRealtime]),
 * never [clock]/[java.time.Instant]: wall-clock time can be moved forward or backward at any
 * moment by the user, NTP sync, or the network, none of which reflect how much real device time
 * has actually elapsed. This budget also belongs to the whole routine OCCURRENCE, not to a single
 * worker instance — see [RoutineOccurrenceRuntimeRepository] and [establishOccurrenceRuntimeState]
 * for how a replacement worker (WorkManager restarting this routine's unique work while the same
 * occurrence is still open) continues counting from the ORIGINAL start rather than being handed a
 * fresh allowance, and how a device reboot (which resets the monotonic clock itself) falls back to
 * a conservative wall-clock check instead of silently granting one either. */
internal const val HARD_FOREGROUND_RUNTIME_CAP_MINUTES = 330L

/**
 * Runs one routine's active window end to end: checks notification availability, enters
 * foreground execution immediately with a valid notification, fetches and shows departures
 * right away — never waiting on disruptions, which are fetched only AFTER departures have
 * already posted, bounded by a short timeout, and folded in with a second, silent notification
 * update only if they change what's already shown (see the main loop's own comment) — then
 * re-fetches and silently updates the SAME notification (stable
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
 * Also re-checks the routine's existence/enabled/paused-for-today state, AND notification
 * availability, on every single loop tick (not just once at the start) — an edit, disable,
 * pause, or delete that happens while this worker is already running, or the user turning
 * notifications off partway through the window, takes effect on its very next tick, stopping
 * the loop, removing the notification, and (if the routine still exists) rescheduling its next
 * eligible occurrence. Without this, the loop would otherwise keep fetching departures and
 * burning battery/network/backend requests for the rest of the window even though
 * [RoutineNotifier.showOrUpdate] itself would silently refuse to post anything once
 * notifications are unavailable.
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
 * as a normal window-end completion. [routineNotifier.remove] runs in a `finally` once
 * foreground execution actually started, whichever of these paths is taken — but ONLY if this
 * run is still the recorded content owner at that point (see [claimContentOwnership] and
 * [se.blick.app.data.repository.RoutineWorkOwnershipRepository]'s own doc): a cancelled run
 * that has since been superseded by a replacement (e.g. editing this active routine, which
 * cancels this worker and immediately starts a new one for the same routine id) must never let
 * its own cleanup clear content the replacement has already posted. This is deliberately NOT
 * "skip cleanup on every cancellation" — a cancelled run that is STILL the recorded owner
 * (nothing has actually replaced it) cleans up exactly as before; only genuine ownership loss
 * suppresses it, so content is never left stale merely because a run happened to be cancelled.
 *
 * Also durably records a pending notification-recovery attempt (see
 * [NotificationRecoveryCoordinator]'s own doc) the moment this worker itself discovers
 * notifications are unavailable — both before entering foreground execution at all, and mid-loop
 * if the condition changes partway through — rather than relying on some other, later observer
 * to notice the same thing.
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
    private val getDisruptions: GetDisruptionsUseCase,
    private val staleSnapshotRepository: StaleSnapshotRepository,
    private val routineNotifier: RoutineNotifier,
    private val routineNotificationBuilder: RoutineNotificationBuilder,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val routineScheduler: RoutineScheduler,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val notificationRecoveryReporter: NotificationRecoveryReporter,
    private val routineWorkOwnershipRepository: RoutineWorkOwnershipRepository,
    private val routineOccurrenceRuntimeRepository: RoutineOccurrenceRuntimeRepository,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
    private val elapsedRealtimeProvider: ElapsedRealtimeProvider,
    private val bootCountProvider: BootCountProvider,
) : CoroutineWorker(context, params) {

    private fun zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())

    override suspend fun doWork(): Result {
        val routineId = inputData.getString(KEY_ROUTINE_ID)
        if (routineId == null) {
            // Malformed WorkRequest -- there is no routine identity to look up or reschedule
            // around, but some OTHER routine's widget state may currently be stale (e.g. this
            // is why the whole run exists), so reconcile from scratch rather than leaving
            // whatever was last rendered untouched. Best-effort -- see runWidgetUpdateSafely's
            // own doc for why a widget failure here must never turn into a worker crash.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.failure()
        }
        val routine = routineRepository.getById(routineId)
        if (routine == null) {
            // Deleted (or never existed) -- nothing to schedule, and the widget must not be
            // left showing a routine that no longer exists.
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }
        if (!routine.enabled) {
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }

        // Defensive backstop for a routine already enqueued (in WorkManager's own durable
        // queue) before this limit existed, or one written by a future code change or a
        // corrupted/edited database -- WorkManagerRoutineScheduler.scheduleActivation already
        // refuses to enqueue such a routine going forward, but that check can't retroactively
        // reach a OneTimeWorkRequest that was persisted before this code shipped. Never enters
        // foreground execution, never crashes, never modifies the stored routine -- just
        // cancels any (now-redundant) scheduled work and leaves it for the user to correct.
        val durationValidation = RoutineDurationValidator.validateSelf(routine)
        if (durationValidation is RoutineDurationValidationResult.ExceedsDailyLimit) {
            Log.w(
                LOG_TAG,
                "Routine $routineId exceeds the daily active-duration limit " +
                    "(${durationValidation.totalMinutes}min on ${durationValidation.weekday}); " +
                    "refusing to activate it. Leaving it stored so the user can correct it.",
            )
            routineScheduler.cancelActivation(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }

        val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, zonedNow(), excludedDate = routine.pausedDate)
        if (occurrence !is NextOccurrence.ActiveNow) {
            // Either started late (the intended window already fully elapsed) or was
            // somehow enqueued before its window opened -- either way, this specific
            // occurrence is no longer valid to run. Reschedule the next eligible one instead
            // of running (or partially running) a stale window. No foreground execution, no
            // notification, ever entered for this case.
            rescheduleNext(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }
        val windowEnd = occurrence.windowEnd

        // Checked BEFORE any foreground execution or notification/channel construction begins
        // (see class doc) -- if unavailable, this occurrence is skipped exactly like a late
        // start above: reschedule the next eligible one and exit, without ever calling
        // setForeground, building a notification, or touching the channel. The window IS
        // genuinely active here (unlike the reconcile() cases above), so the widget must say so
        // honestly rather than either staying on Loading forever or claiming "No active
        // commute." -- see RoutineWidgetUpdater.showNotificationsUnavailable's own doc.
        if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) {
            // Durably records that a recovery attempt is owed BEFORE this run stops -- see
            // NotificationRecoveryCoordinator's own doc on why this is one of the required
            // real detection paths, and why it must happen here rather than being inferred
            // later from a compared availability snapshot.
            notificationRecoveryReporter.reportUnavailable()
            // Skips today's still-open occurrence rather than recomputing it -- see
            // rescheduleSkippingToday's own doc for why a plain rescheduleNext here would loop.
            rescheduleSkippingToday(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.showNotificationsUnavailable(routine) }
            return Result.success()
        }

        var enteredForeground = false
        var notificationsBecameUnavailable = false
        var handledFailure = false
        var hitHardRuntimeCap = false
        var lastKnownRoutine = routine
        // Persists across loop ticks -- see the main loop's own comment on why a timed-out or
        // failed disruptions fetch falls back to this rather than dropping to "no disruption
        // shown" for one tick. Re-checked against its own validUntil every tick (see the main
        // loop's own comment) so an already-expired fallback can never be shown indefinitely
        // just because every subsequent fetch happens to time out or fail.
        var lastKnownDisruption: Disruption? = null

        try {
            setForeground(createForegroundInfo(routine))
            enteredForeground = true
            claimContentOwnership(routineId)

            // Belongs to this OCCURRENCE (identified by windowEnd), not to this worker instance
            // -- see establishOccurrenceRuntimeState's own doc. A replacement worker restarted
            // for the same occurrence reuses the same recorded start rather than being handed a
            // fresh HARD_FOREGROUND_RUNTIME_CAP_MINUTES allowance.
            val occurrenceRuntime = establishOccurrenceRuntimeState(routineId, windowEnd)

            while (true) {
                val current = routineRepository.getById(routineId) ?: break
                lastKnownRoutine = current
                if (!current.enabled) break
                if (current.pausedDate == zonedNow().toLocalDate()) break
                if (!zonedNow().isBefore(windowEnd)) break
                // Independent of windowEnd -- see HARD_FOREGROUND_RUNTIME_CAP_MINUTES's own doc
                // on why real elapsed time can exceed the configured window's clock-face
                // duration, and why this worker must stop regardless of what windowEnd says once
                // that happens.
                if (hasReachedHardRuntimeCap(occurrenceRuntime)) {
                    Log.w(
                        LOG_TAG,
                        "Routine $routineId's occurrence has run for " +
                            "${HARD_FOREGROUND_RUNTIME_CAP_MINUTES}min real elapsed time -- " +
                            "stopping now as a safety backstop regardless of its configured " +
                            "window end, rather than risk exceeding Android's own foreground " +
                            "time limit.",
                    )
                    hitHardRuntimeCap = true
                    break
                }
                if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) {
                    notificationsBecameUnavailable = true
                    // See the pre-loop check's identical call for why this must happen here,
                    // before the loop actually breaks, not be inferred later.
                    notificationRecoveryReporter.reportUnavailable()
                    break
                }

                val identity = current.departureIdentity()
                val previous = staleSnapshotRepository.get(routineId, identity)

                // Departures are fetched and posted ALONE first -- disruptions are never
                // awaited before this notification goes out. GetLiveDeparturesUseCase itself
                // never throws (besides a real CancellationException, which propagates and ends
                // this run exactly like any other failure here would -- see doWork's own
                // CancellationException handling).
                val departuresState = getLiveDepartures(current, previous = previous).last()
                if (departuresState is LiveDeparturesState.Live) {
                    staleSnapshotRepository.save(routineId, identity, departuresState.snapshot)
                }

                val now = clock.instant()
                // A fallback carried over from an earlier tick may have expired by now (its own
                // validUntil has passed) even though nothing has explicitly replaced it yet --
                // only a fresh Loaded/NoDisruptions result normally updates lastKnownDisruption
                // (see below), so without this check an expired disruption could keep being
                // shown indefinitely across ticks where every subsequent fetch times out or
                // fails. A confirmed-fresh result is never expired at the moment it's received,
                // so this can only ever clear a fallback, never a value from THIS tick's own fetch.
                if (lastKnownDisruption?.validUntil?.isBefore(now) == true) {
                    lastKnownDisruption = null
                }
                val disruptionAtPost = lastKnownDisruption
                val model = RoutineNotificationMapper.map(current, departuresState, now, disruptionAtPost)
                // The real NotificationPostResult is intentionally not surfaced anywhere from
                // here (there is no UI attached to a background worker to report it to) --
                // but it is also never used to claim success; showOrUpdate itself already
                // fails safely (see AndroidRoutineNotifier) if app notifications or the Blick
                // channel are disabled, which this worker relies on rather than duplicating.
                routineNotifier.showOrUpdate(model)
                // Same tick, same already-fetched departuresState -- no separate fetch, no
                // separate timer (see RoutineWidgetUpdater's own doc). Best-effort: without
                // runWidgetUpdateSafely, a widget/Glance/DataStore failure here would fall into
                // this function's own outer `catch (e: Exception)` below and be treated as a
                // "handled failure" -- cutting the whole active-window loop short even though
                // the notification above already posted successfully this tick.
                runWidgetUpdateSafely { routineWidgetUpdater.updateWithDepartures(current, departuresState, now, disruptionAtPost) }

                // Disruptions are fetched only AFTER departures have already posted, bounded by
                // DISRUPTIONS_FETCH_TIMEOUT_MS so a slow SL Deviations request can never delay --
                // or, worse, indefinitely hang -- the departures notification above.
                // GetDisruptionsUseCase itself never throws (besides a real
                // CancellationException -- see this loop's own CancellationException handling),
                // so only the timeout is needed to bound how long a slow-but-not-actually-failing
                // request can run.
                //
                // A timed-out or genuinely failed (Unavailable) fetch falls back to
                // lastKnownDisruption (whichever disruption was last successfully confirmed,
                // persisted across ticks) rather than dropping to "no disruption shown" purely
                // because this one tick's request was slow -- a confirmed NoDisruptions result,
                // on the other hand, DOES clear it, since that is a genuine, positive
                // confirmation that nothing is currently affecting this routine, not merely an
                // absence of information.
                val disruptionFetchStart = clock.instant()
                when (val disruptionResult = withTimeoutOrNull(DISRUPTIONS_FETCH_TIMEOUT_MS) { getDisruptions(current).last() }) {
                    is DisruptionsState.Loaded -> lastKnownDisruption = disruptionResult.disruptions.firstOrNull()
                    is DisruptionsState.NoDisruptions -> lastKnownDisruption = null
                    is DisruptionsState.Unavailable, is DisruptionsState.Loading, null -> Unit
                }
                // Only re-posts the ALREADY-SHOWN notification with a refreshed disruption line
                // when the fetch above actually changed what's known -- "update the notification
                // afterward if needed," not an unconditional second post every tick. showOrUpdate
                // is a silent, stable-id update either way (setOnlyAlertOnce -- see
                // RoutineNotificationBuilder), so this never re-alerts the user.
                if (lastKnownDisruption != disruptionAtPost) {
                    routineNotifier.showOrUpdate(RoutineNotificationMapper.map(current, departuresState, now, lastKnownDisruption))
                    // Mirrors the notification's own second, disruption-aware update above --
                    // same tick, same already-fetched departuresState, no separate widget fetch
                    // or timer.
                    runWidgetUpdateSafely { routineWidgetUpdater.updateWithDepartures(current, departuresState, now, lastKnownDisruption) }
                }

                // Subtracts however long the disruptions fetch just above actually took (bounded
                // by DISRUPTIONS_FETCH_TIMEOUT_MS, but a slow-yet-successful fetch can still
                // consume most of that) from this tick's own delay, rather than adding it on top
                // -- without this, a slow-but-not-timed-out fetch drifted total tick spacing up
                // to DISRUPTIONS_FETCH_TIMEOUT_MS beyond ACTIVE_WINDOW_REFRESH_INTERVAL_MS (up to
                // ~35s instead of 30s) on every tick where the fetch was slow.
                val disruptionFetchElapsedMs = Duration.between(disruptionFetchStart, clock.instant()).toMillis().coerceAtLeast(0L)
                delay((ACTIVE_WINDOW_REFRESH_INTERVAL_MS - disruptionFetchElapsedMs).coerceAtLeast(0L))
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
            // handledFailure = true because the window may still genuinely be open (this
            // failure is unrelated to window timing) -- see rescheduleSkippingToday's own doc.
            handledFailure = true
        } finally {
            if (enteredForeground) {
                // Ownership may have been superseded by a replacement run (e.g. editing this
                // active routine cancelled this worker and immediately started a new one) --
                // only clean up if THIS run is still the recorded owner of the content it
                // posted, so a cancelled/replaced run can never clobber a newer run's own
                // notification/widget content. The check itself must run even while this
                // coroutine is being cancelled (that's precisely the case it exists to guard),
                // so it's wrapped in NonCancellable; on any unexpected failure to even determine
                // ownership, this fails CLOSED (skips cleanup) rather than risking a clear
                // against unknown ownership -- see this worker's own class doc.
                val stillOwnsContent = withContext(NonCancellable) {
                    try {
                        routineWorkOwnershipRepository.isOwner(routineId, id.toString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to determine content ownership for routine $routineId; skipping cleanup to be safe", e)
                        false
                    }
                }
                if (stillOwnsContent) {
                    routineNotifier.remove()
                    if (notificationsBecameUnavailable) {
                        // The window is still genuinely open -- represent that honestly instead of
                        // clearing to "No active commute." (see the pre-loop check's own comment).
                        // Best-effort: a widget failure inside a `finally` must never replace an
                        // in-flight CancellationException or exception being propagated out of this
                        // block -- runWidgetUpdateSafely still rethrows a real CancellationException
                        // unconverted, but swallows anything else so it can never mask whatever this
                        // `finally` was already unwinding.
                        runWidgetUpdateSafely { routineWidgetUpdater.showNotificationsUnavailable(lastKnownRoutine) }
                    } else {
                        runWidgetUpdateSafely { routineWidgetUpdater.clear() }
                    }
                }
            } else {
                // setForeground() itself threw before the loop -- and before this worker ever
                // posted anything for the widget -- so there is nothing to "clear"; reconcile
                // instead so the widget doesn't keep showing whatever it displayed before this
                // run started (e.g. a stale Loading from an earlier attempt).
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            }
        }

        // This occurrence is over one way or another (naturally, or via the hard cap) -- clear
        // its runtime tracking so a genuinely NEW occurrence later never has to share space with
        // stale data (though its own different windowEnd would already make old state
        // irrelevant on its own -- see establishOccurrenceRuntimeState). Best-effort: a Room
        // failure here must never prevent the reschedule decision below from running.
        try {
            routineOccurrenceRuntimeRepository.clear(routineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to clear occurrence runtime state for routine $routineId", e)
        }

        // notificationsBecameUnavailable/handledFailure/hitHardRuntimeCap: the loop broke or the
        // run failed for a reason unrelated to the window itself actually ending, so the window
        // may still be genuinely open (windowEnd, per the routine's configured local time, may
        // not have been reached yet) -- see rescheduleSkippingToday's own doc for why a plain
        // rescheduleNext here would loop.
        if (notificationsBecameUnavailable || handledFailure || hitHardRuntimeCap) {
            rescheduleSkippingToday(routineId)
        } else {
            rescheduleNext(routineId)
        }
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

    /** Like [rescheduleNext], except today's occurrence is treated as ineligible for THIS one
     * scheduling calculation — used when the reason this run is ending is notifications being
     * unavailable (at startup or discovered mid-loop) or a handled failure (e.g. [setForeground]
     * itself throwing), none of which mean the window itself has ended. Without this, plain
     * [rescheduleNext] would ask [RoutineScheduler] to recompute the SAME still-open occurrence,
     * which [WorkManagerRoutineScheduler] schedules with a ZERO initial delay for
     * [NextOccurrence.ActiveNow] — causing this worker to re-run almost immediately, hit the
     * exact same condition again, and spin in a tight zero-delay loop until either the window
     * naturally ends or the condition resolves.
     *
     * Deliberately does NOT call [RoutineRepository.pauseForDate] — that would persist a real,
     * user-visible pause the user never asked for. Instead, today is threaded through the exact
     * same [CommuteRoutine.pausedDate] field [WorkManagerRoutineScheduler] already reads for
     * "paused for today", but only on this in-memory copy passed to [RoutineScheduler] for this
     * one call — nothing is written back via [RoutineRepository], so the routine's real
     * [CommuteRoutine.pausedDate] (and everything the widget/UI show for it) is completely
     * unaffected, and the very next normal [scheduleActivation][RoutineScheduler.scheduleActivation]
     * call (the next natural reschedule, or any lifecycle mutation) recomputes fresh as usual.
     *
     * Always overwrites [CommuteRoutine.pausedDate] with [today] on this temporary copy — never
     * `latest.pausedDate ?: today`, which was a real bug: if the routine already carried a
     * STALE pausedDate from an earlier, unrelated "pause today" (e.g. still set to yesterday,
     * simply never cleared), the `?:` left that old date in place instead of today's, so
     * [NextOccurrenceCalculator] no longer excluded TODAY's still-open window at all — the very
     * next [scheduleActivation][RoutineScheduler.scheduleActivation] call recomputed the SAME
     * occurrence as [NextOccurrence.ActiveNow] again, [WorkManagerRoutineScheduler] enqueued it
     * with a zero initial delay again, and the worker re-ran immediately into the exact same
     * exit condition — a genuine zero-delay busy loop, not merely a theoretical one. */
    private suspend fun rescheduleSkippingToday(routineId: String) {
        val latest = routineRepository.getById(routineId) ?: return
        val today = zonedNow().toLocalDate()
        routineScheduler.scheduleActivation(latest.copy(pausedDate = today))
    }

    /** Claims this run as the current content owner for [routineId] — see
     * `data/local/room/RoutineWorkOwnershipEntity.kt`'s own doc for exactly what this protects
     * against. Called once, as early as possible after entering foreground execution (right
     * after posting the very first, "Loading" notification). Best-effort: if this write fails,
     * the run still proceeds normally rather than aborting over ownership bookkeeping alone —
     * see this worker's own class doc on why "always clean up" is not the fallback either way,
     * regardless of whether claiming itself succeeded. */
    private suspend fun claimContentOwnership(routineId: String) {
        try {
            routineWorkOwnershipRepository.claim(routineId, id.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to claim content ownership for routine $routineId", e)
        }
    }

    /** Returns the [RoutineOccurrenceRuntimeState] this run should measure
     * [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] against — either a FRESH one (a genuinely new
     * occurrence, identified by [windowEnd]; or nothing usable was persisted, e.g. a Room
     * failure reading it), or the ALREADY-PERSISTED one from an earlier worker instance for this
     * SAME occurrence, reused as-is so a replacement worker (or one restarted after a reboot)
     * continues counting from the ORIGINAL start rather than being handed a fresh allowance.
     * [RoutineOccurrenceRuntimeState.occurrenceWindowEndEpochMilli] is what distinguishes "same
     * occurrence" from "a new one" — every occurrence of a routine has a distinct [windowEnd].
     *
     * Called once, right after entering foreground execution, since establishing (or persisting
     * a freshly-established) state is itself irrelevant to a run that never gets that far (the
     * pre-loop late-start/notifications-unavailable early returns reschedule and exit before any
     * of this matters).
     *
     * Best-effort on both the read and the write: a Room failure here must never prevent this
     * run's foreground loop from proceeding — it just means this one run's hard-cap measurement
     * starts fresh from now (in memory only) rather than continuing a previous run's count,
     * which is the same conservative direction as this whole cap already errs in (stopping
     * sooner, never later, than a perfectly-continuous count would). */
    private suspend fun establishOccurrenceRuntimeState(routineId: String, windowEnd: ZonedDateTime): RoutineOccurrenceRuntimeState {
        val occurrenceIdentityMillis = windowEnd.toInstant().toEpochMilli()
        val existing = try {
            routineOccurrenceRuntimeRepository.get(routineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read occurrence runtime state for routine $routineId; starting a fresh runtime budget for this run", e)
            null
        }
        if (existing != null && existing.occurrenceWindowEndEpochMilli == occurrenceIdentityMillis) {
            return existing
        }
        val fresh = RoutineOccurrenceRuntimeState(
            occurrenceWindowEndEpochMilli = occurrenceIdentityMillis,
            monotonicStartElapsedRealtimeMillis = elapsedRealtimeProvider.elapsedRealtimeMillis(),
            bootCountAtStart = bootCountProvider.currentBootCount(),
            hardStopEpochMilli = clock.instant().toEpochMilli() + Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis(),
        )
        try {
            routineOccurrenceRuntimeRepository.save(routineId, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to persist occurrence runtime state for routine $routineId; the hard cap still applies for this run using an in-memory start", e)
        }
        return fresh
    }

    /** Whether [state]'s occurrence has now run for [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] of
     * REAL elapsed time. While the device's boot count still matches
     * [RoutineOccurrenceRuntimeState.bootCountAtStart], [elapsedRealtimeProvider] (monotonic,
     * unaffected by wall-clock changes) is the authoritative measurement. Once the boot count no
     * longer matches, the device has rebooted since this occurrence started —
     * [android.os.SystemClock.elapsedRealtime] has been reset by the OS and comparing against
     * [RoutineOccurrenceRuntimeState.monotonicStartElapsedRealtimeMillis] directly would be
     * meaningless — so this falls back to [RoutineOccurrenceRuntimeState.hardStopEpochMilli], an
     * absolute wall-clock instant computed once when the occurrence first began, which survives
     * a reboot precisely because it is NOT itself reset by one. Either way, this never grants a
     * fresh [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] allowance just because the worker (or the
     * device) restarted. */
    private fun hasReachedHardRuntimeCap(state: RoutineOccurrenceRuntimeState): Boolean {
        val capMillis = Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis()
        return if (bootCountProvider.currentBootCount() == state.bootCountAtStart) {
            val elapsedMillis = elapsedRealtimeProvider.elapsedRealtimeMillis() - state.monotonicStartElapsedRealtimeMillis
            elapsedMillis >= capMillis
        } else {
            clock.instant().toEpochMilli() >= state.hardStopEpochMilli
        }
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
