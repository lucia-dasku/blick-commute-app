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
import se.blick.app.domain.model.toPresentation
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
import se.blick.app.billing.FreePremiumEntitlementRepository
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.RoutineTierPolicy
import kotlinx.coroutines.flow.first
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.usecase.GetJourneyDisruptionRelevanceUseCase
import se.blick.app.domain.usecase.GetRankedJourneysUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.usecase.RoutineScheduleOverlapValidator
import se.blick.app.domain.usecase.compactJourneyPlannerPresentation
import se.blick.app.domain.usecase.compactPresentation
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.domain.usecase.effectiveFirstDeparture
import se.blick.app.domain.usecase.filterCurrentJourneys
import se.blick.app.domain.usecase.isCurrentJourney
import se.blick.app.domain.usecase.primaryDisruptionNotices
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
 * create/edit validation has no date/zone to see this with). Two distinct DST risks exist, and
 * this one constant covers both, though only one of them needs a REDUCED effective cap:
 *
 * - **Autumn ("fall back")**: a single occurrence's own local-clock window can span the repeated
 *   hour, so a nominally [se.blick.app.domain.usecase.MAX_DAILY_ACTIVE_MINUTES]-long (5h)
 *   configured window can genuinely take up to an hour longer in real time than its clock-face
 *   duration suggests — up to 6 real hours. This constant alone (330min/5h30m) already stops that
 *   safely, comfortably under Android's actual six-hour `dataSync` foreground-service limit, with
 *   no extra mechanism needed — see [doWork]'s main loop, which checks this independently of
 *   `windowEnd` on every tick.
 * - **Spring ("spring forward")**: the real gap between one occurrence's start and the NEXT one's
 *   can be shortened to as little as 23 real hours (e.g. Stockholm: Saturday 07:00 to Sunday
 *   07:00). If BOTH occurrences were allowed the full 330 minutes, up to 330 + (24h - 23h) = 390
 *   minutes of combined foreground time could land inside a single real rolling 24-hour window —
 *   over Android's limit. [effectiveHardCapMinutes] detects exactly this (using real
 *   [ZonedDateTime]/[NextOccurrenceCalculator] arithmetic, never a hardcoded date or
 *   `isDaylightSavings` check) and reduces the CURRENT occurrence's own effective cap just enough
 *   to keep the combined total at or under this constant — see that function's own doc for the
 *   exact calculation, and [RoutineOccurrenceRuntimeState] for how the reduced value is persisted
 *   and enforced identically whether this same worker instance keeps running, a replacement
 *   worker takes over, or the device reboots mid-occurrence.
 *
 * Measured via [ElapsedRealtimeProvider] (backed by [android.os.SystemClock.elapsedRealtime]),
 * never [clock]/[java.time.Instant]: wall-clock time can be moved forward or backward at any
 * moment by the user, NTP sync, or the network, none of which reflect how much real device time
 * has actually elapsed. This budget also belongs to the whole routine OCCURRENCE, not to a single
 * worker instance — see [RoutineOccurrenceRuntimeRepository] and
 * [createFreshOccurrenceRuntimeState] for how a replacement worker (WorkManager restarting this
 * routine's unique work while the same occurrence is still open) continues counting from the
 * ORIGINAL start rather than being handed a fresh allowance, and how a device reboot (which
 * resets the monotonic clock itself) falls back to a conservative wall-clock check instead of
 * silently granting one either. */
internal const val HARD_FOREGROUND_RUNTIME_CAP_MINUTES = 330L

/**
 * The real-elapsed-time cap THIS SPECIFIC occurrence ([windowStart]..[windowEnd], both already
 * resolved to real instants via [NextOccurrenceCalculator]) should be measured against: normally
 * [HARD_FOREGROUND_RUNTIME_CAP_MINUTES], but reduced when the gap to [routine]'s own NEXT genuine
 * occurrence has been shortened by a daylight-saving transition — see
 * [HARD_FOREGROUND_RUNTIME_CAP_MINUTES]'s own doc for why only the "spring forward" direction
 * needs this (a "fall back" transition only ever LENGTHENS the gap between two same-clock-time
 * starts, never shortens it).
 *
 * Deliberately reuses [NextOccurrenceCalculator] (unmodified) rather than any new date/timezone
 * logic: calling it with THIS occurrence's own [windowEnd] as the reference instant correctly
 * finds [routine]'s next eligible occurrence strictly after today, using the exact same DST-aware
 * [ZonedDateTime] resolution the rest of the scheduling path already relies on.
 *
 * Two conditions must BOTH hold before any reduction applies:
 *
 * 1. The real gap to the next occurrence must itself be UNDER 24 real hours. A next occurrence
 *    24 real hours or more away can never share any single rolling 24-hour window with THIS one
 *    at all, however much DST distortion happens to sit somewhere between them — e.g. a routine
 *    active Friday and the following Monday spans a real gap of roughly 71 hours even across a
 *    spring transition, nowhere near the danger zone, and must NOT be reduced just because a
 *    transition happens to fall somewhere in that multi-day span. Checked first, using the REAL
 *    gap alone, before the local-vs-real comparison below is even computed.
 * 2. Only once that holds does the real gap need to be SHORTER than the LOCAL one — the real
 *    world diverging from what [se.blick.app.domain.usecase.RoutineDurationValidator]'s own
 *    synthetic model assumes, i.e. a spring-forward transition sitting between the two
 *    occurrences — for there to be any shortfall to protect against. [CommuteRoutine] has a
 *    single, fixed start/end time applied to EVERY active day, so the closest two of a routine's
 *    own occurrences can ever be, in LOCAL terms, is exactly 1440 minutes apart (consecutive
 *    active days, same clock time each day); what can make the REAL gap shorter than that is only
 *    ever a DST transition sitting between them, never a difference in configured clock time —
 *    with no such transition nearby, the real gap tracks the local one exactly, so this never
 *    reduces the cap either.
 *
 * The reduction itself compares two different ways of measuring the same gap:
 * - the REAL gap: [Duration.between] the two occurrences' own [ZonedDateTime]s (zone/DST-aware).
 * - the LOCAL (clock-face) gap: [Duration.between] the same two starts as plain
 *   [java.time.LocalDateTime]s (zone-naive).
 *
 * For the standard Stockholm case (23 real hours where 24 local hours were expected — comfortably
 * under the 24-hour gate above), the shortfall is 60 minutes, so the effective cap becomes
 * 330 - 60 = 270 — see `EffectiveHardCapMinutesTest` for this, and the Friday-to-Monday
 * counterexample above, both verified with real `java.time`/`Europe/Stockholm` arithmetic against
 * the actual 2027 transition dates.
 *
 * Never negative: reduces the cap to zero at most, however large a shortfall this ever computes
 * (not expected to exceed one DST hour in practice for any real [java.time.ZoneId]).
 */
internal fun effectiveHardCapMinutes(routine: CommuteRoutine, windowStart: ZonedDateTime, windowEnd: ZonedDateTime): Long {
    val next = NextOccurrenceCalculator.nextOccurrence(routine, windowEnd, excludedDate = routine.pausedDate)
    val nextStart = (next as? NextOccurrence.Upcoming)?.windowStart ?: return HARD_FOREGROUND_RUNTIME_CAP_MINUTES

    val realGapMinutes = Duration.between(windowStart, nextStart).toMinutes()
    if (realGapMinutes >= 24 * 60) return HARD_FOREGROUND_RUNTIME_CAP_MINUTES

    val localGapMinutes = Duration.between(windowStart.toLocalDateTime(), nextStart.toLocalDateTime()).toMinutes()
    val shortfallMinutes = (localGapMinutes - realGapMinutes).coerceAtLeast(0)

    return (HARD_FOREGROUND_RUNTIME_CAP_MINUTES - shortfallMinutes).coerceAtLeast(0)
}

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
 * as a normal window-end completion. [routineNotifier.remove] runs in a `finally`, whichever of
 * these paths is taken — but ONLY if this run is still the recorded content owner at that point
 * (see [claimContentOwnership] and [se.blick.app.data.repository.RoutineWorkOwnershipRepository]'s
 * own doc): a cancelled run that has since been superseded by a replacement (e.g. editing this
 * active routine, which cancels this worker and immediately starts a new one for the same
 * routine id) must never let its own cleanup clear content the replacement has already posted.
 * This is deliberately NOT "skip cleanup on every cancellation" — a cancelled run that is STILL
 * the recorded owner (nothing has actually replaced it) cleans up exactly as before; only genuine
 * ownership loss suppresses it, so content is never left stale merely because a run happened to
 * be cancelled.
 *
 * **Ownership is claimed BEFORE [setForeground], not after.** [claimContentOwnership] runs, and
 * must succeed, before this worker posts anything at all — reversing that order (claim only
 * once content has already been posted) leaves a window where a REPLACEMENT run's freshly-posted
 * notification is on screen but the ownership record still names the OLD run, so the old run's
 * own `finally` (checking [se.blick.app.data.repository.RoutineWorkOwnershipRepository.isOwner])
 * would wrongly see itself as still the owner and delete the replacement's content out from under
 * it. Claiming first closes that window: by the time any content exists, the ownership record
 * already names whichever run posted it. A failed claim is treated as a required precondition,
 * not a best-effort step — this run refuses to enter foreground execution at all rather than post
 * content it cannot prove it owns (see [claimContentOwnership]'s own doc). Once ownership is
 * claimed, the `finally` block's cleanup runs unconditionally (gated only by
 * [se.blick.app.data.repository.RoutineWorkOwnershipRepository.isOwner], never by whether
 * [setForeground] itself went on to succeed) — so if [setForeground] throws right after a
 * successful claim, this run still safely reconciles whatever content exists via the exact same
 * ownership-aware mechanism, rather than a separate path.
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
    private val entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore? = null,
    private val getRankedJourneys: GetRankedJourneysUseCase? = null,
    private val getJourneyDisruptionRelevance: GetJourneyDisruptionRelevanceUseCase? = null,
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
        // Hilt always supplies the selection store in production. Keeping this dependency
        // optional lets focused worker tests (and previews) construct the worker without also
        // having to implement an unrelated all-routines stream.
        if (freeRoutineSelectionStore != null) {
            val allRoutines = routineRepository.observeAll().first()
            if (RoutineScheduleOverlapValidator.nonOverlapping(allRoutines).none { it.id == routine.id } ||
                !RoutineTierPolicy.canRun(
                    routine, allRoutines, entitlementRepository.entitlement.value,
                    freeRoutineSelectionStore.selectedRoutineId.value,
                )
            ) {
                routineScheduler.cancelActivation(routineId)
                runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
                return Result.success()
            }
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
        val windowStartDate = if (routine.endTime.isAfter(routine.startTime)) {
            windowEnd.toLocalDate()
        } else {
            windowEnd.toLocalDate().minusDays(1)
        }
        val windowStart = ZonedDateTime.of(windowStartDate, routine.startTime, windowEnd.zone)

        // Read BEFORE any foreground execution begins, and reused below rather than re-read --
        // both so an already-exhausted occurrence (a replacement worker or reboot-recovered
        // process picking up where an earlier run left off after hitting
        // HARD_FOREGROUND_RUNTIME_CAP_MINUTES -- see RoutineOccurrenceRuntimeRepository's own
        // doc on why that earlier run's record is never cleared) never even briefly re-enters
        // foreground, and so a read failure here can be treated with the same fail-safe caution
        // as "possibly already exhausted" rather than optimistically as "definitely fresh" -- see
        // this call's own catch below. A WRITE failure, later, when a genuinely fresh occurrence's
        // state is first durably saved, is treated with that exact same caution, not more
        // leniently -- see createFreshOccurrenceRuntimeState's own doc and its call site's `?: run
        // { ... }` fallback, which refuses foreground entry just the same as this READ failure
        // does.
        val persistedRuntimeState = try {
            routineOccurrenceRuntimeRepository.get(routineId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                LOG_TAG,
                "Failed to read occurrence runtime state for routine $routineId before entering " +
                    "foreground; refusing to grant foreground time until this can be confirmed " +
                    "safe, rather than risking a hidden already-exhausted occurrence being treated " +
                    "as a fresh one",
                e,
            )
            rescheduleSkippingToday(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }
        // Only a state matching THIS exact occurrence (same windowEnd) is relevant here -- any
        // other value belongs to an earlier, different occurrence and is simply stale (see
        // createFreshOccurrenceRuntimeState, which overwrites it the normal way once this run
        // proceeds).
        val matchingRuntimeState = persistedRuntimeState?.takeIf { it.occurrenceWindowEndEpochMilli == windowEnd.toInstant().toEpochMilli() }
        if (matchingRuntimeState != null && hasReachedHardRuntimeCap(matchingRuntimeState)) {
            Log.w(
                LOG_TAG,
                "Routine $routineId's occurrence already exhausted its persisted real-elapsed-time " +
                    "cap before this run even started -- not re-entering foreground execution.",
            )
            rescheduleSkippingToday(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }

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

        // A genuinely fresh occurrence (matchingRuntimeState is null -- nothing usable was
        // persisted above) needs its own runtime state established and DURABLY saved BEFORE
        // foreground execution begins, not after: if the save fails, this refuses foreground
        // entry exactly like the read failure above, rather than silently continuing on an
        // in-memory-only budget a replacement worker or reboot could never see -- see
        // createFreshOccurrenceRuntimeState's own doc.
        val occurrenceRuntime = matchingRuntimeState ?: createFreshOccurrenceRuntimeState(routine, windowStart, windowEnd) ?: run {
            rescheduleSkippingToday(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }

        // Ownership is claimed BEFORE any content is posted -- not after -- so an old worker's
        // own `finally` (checking isOwner below) can never observe itself as "still the owner"
        // during the window where this run has already posted a replacement notification but
        // not yet recorded that fact. Required to proceed, not best-effort: if this fails, this
        // run has no confirmed right to the content it would otherwise post, so it refuses to
        // enter foreground execution at all, exactly like the runtime-state read/write failures
        // above -- see claimContentOwnership's own doc.
        if (!claimContentOwnership(routineId)) {
            rescheduleSkippingToday(routineId)
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            return Result.success()
        }

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

            while (true) {
                // Captured before ANY of this tick's own work -- the routine re-read below, the
                // departures fetch, caching, notification/widget rendering, and the disruptions
                // fetch further down -- so the delay computed at the bottom of this loop reflects
                // the WHOLE tick's real elapsed time, not just one piece of it (see this loop's
                // own comment at that delay call). Monotonic (SystemClock.elapsedRealtime-backed,
                // see ElapsedRealtimeProvider's own doc), never clock/Instant: wall-clock time can
                // jump forward or backward at any moment (user changes, NTP sync) without
                // reflecting how much real device time this tick's own work actually took.
                val tickStartElapsedRealtimeMillis = elapsedRealtimeProvider.elapsedRealtimeMillis()

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

                // Departures/journeys are fetched and posted ALONE first -- disruptions are never
                // awaited before this notification goes out. GetLiveDeparturesUseCase itself
                // never throws (besides a real CancellationException, which propagates and ends
                // this run exactly like any other failure here would -- see doWork's own
                // CancellationException handling).
                //
                // Nullable, not List<JourneyPlan> defaulting to emptyList() -- null here means the
                // search itself never completed (an exception, or a malformed routine missing an
                // origin/destination id), while a non-null-but-empty list means it completed fine
                // and genuinely found nothing. Collapsing those two into the same emptyList() was
                // a real bug: every route below then had no way to tell "the request failed" apart
                // from "the request succeeded with no results", so a perfectly normal empty result
                // (no eligible departure right now, or none within the configured change limit)
                // rendered exactly like a failed fetch -- the notification/widget's own Unavailable
                // copy ("Couldn't load departures right now. Will try again soon.") wrongly telling
                // the user something was broken, while the in-app Routine Details screen (which
                // never conflated the two) correctly showed "No journeys found right now." for the
                // exact same underlying result. See exactProjectionNow below for how this
                // distinction is threaded into both surfaces via LiveDeparturesState.Unavailable
                // vs LiveDeparturesState.NoUpcomingDepartures -- the same split
                // se.blick.app.domain.usecase.LiveDeparturesState's own doc already requires for
                // the plain-departures path -- and RoutineWidgetUpdater.updateWithJourneys's own
                // new fetchFailed parameter for the widget.
                val rawJourneyPlans: List<JourneyPlan>? = if (current.type == RoutineType.EXACT_DESTINATION) {
                    val origin = current.journeyOriginId
                    val destination = current.journeyDestinationId
                    if (origin != null && destination != null && getRankedJourneys != null) {
                        try {
                            // windowEnd is this occurrence's own real active-window end -- see
                            // GetRankedJourneysUseCase's own searchUntil doc for why the backend
                            // needs this real boundary rather than searching unboundedly.
                            // current.changesPreference -- re-read fresh from this tick's own
                            // routine lookup above, never a value captured once at schedule time,
                            // so a preference change picked up between ticks (see
                            // RoutineDetailsViewModel.updateChangesPreference) takes effect on the
                            // very next tick without any dedicated refresh path.
                            getRankedJourneys(
                                origin, destination, current.allowedJourneyTransportModes,
                                windowEnd.toInstant(), current.changesPreference,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                } else emptyList()
                // Captured, and re-filtered against, immediately after getRankedJourneys returns --
                // GetRankedJourneysUseCase itself already filtered defensively against ITS OWN
                // post-response `now` (see that class's own doc), but that call's result still has
                // to travel back through this coroutine before this line runs, and a journey that
                // was genuinely still current a moment ago can have departed in the meantime. Never
                // trusting rawJourneyPlans as already-current a second time here is what lets this
                // worker's own tick close that remaining window, rather than merely relying on
                // GetRankedJourneysUseCase's own guarantee.
                //
                // journeyPlans (the re-filtered result) and exactProjectionNow (this same instant)
                // are then the ONE shared eligibility decision used for every surface this tick
                // touches -- the notification projection below, RoutineNotificationMapper's own
                // render, AND routineWidgetUpdater.updateWithJourneys further down all see the
                // identical already-filtered list and the identical instant, so the notification and
                // the widget can never disagree about whether the same journey is still current.
                val exactProjectionNow = clock.instant()
                val journeyPlans = (rawJourneyPlans ?: emptyList()).filterCurrentJourneys(exactProjectionNow)
                val exactProjection = journeyPlans.toExactJourneyNotificationProjection(current, exactProjectionNow)
                val departuresState = if (current.type == RoutineType.EXACT_DESTINATION) {
                    exactProjection?.departuresState ?: if (rawJourneyPlans == null) {
                        LiveDeparturesState.Unavailable
                    } else {
                        LiveDeparturesState.NoUpcomingDepartures(exactProjectionNow)
                    }
                } else {
                    val identity = current.departureIdentity()
                    val previous = staleSnapshotRepository.get(routineId, identity)
                    getLiveDepartures(current, previous = previous).last().also { state ->
                        if (state is LiveDeparturesState.Live) staleSnapshotRepository.save(routineId, identity, state.snapshot)
                    }
                }

                val now = if (exactProjection != null) exactProjectionNow else clock.instant()
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
                val notificationRoutine = exactProjection?.routine ?: current
                // EXACT_DESTINATION's own disruption notices arrive as part of THIS SAME
                // journeyPlans fetch -- no separate fetch to wait for, unlike LINE_DIRECTION's
                // own lastKnownDisruption/getDisruptions below (untouched by this branch).
                // Conservatively aggregated (see compactJourneyPlannerPresentation's own doc: a
                // single distinct PRIMARY notice's own effect, or the generic DISRUPTION label when
                // PRIMARY has several genuinely different ones -- never an invented ranking),
                // and always re-derived from the CURRENT PRIMARY, so a PRIMARY change on a later
                // tick is reflected automatically with no extra bookkeeping.
                val exactDisruption = if (current.type == RoutineType.EXACT_DESTINATION) {
                    journeyPlans.primaryDisruptionNotices().compactJourneyPlannerPresentation()
                } else {
                    null
                }
                val notificationDisruption = exactDisruption ?: disruptionAtPost?.toPresentation()
                val model = RoutineNotificationMapper.map(notificationRoutine, departuresState, now, notificationDisruption)
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
                runWidgetUpdateSafely {
                    if (current.type == RoutineType.EXACT_DESTINATION) {
                        routineWidgetUpdater.updateWithJourneys(
                            current, journeyPlans, now, fetchFailed = rawJourneyPlans == null, disruption = exactDisruption,
                        )
                    } else {
                        routineWidgetUpdater.updateWithDepartures(current, departuresState, now, disruptionAtPost)
                    }
                }

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
                when (val disruptionResult = if (current.type == RoutineType.EXACT_DESTINATION) null else
                    withTimeoutOrNull(DISRUPTIONS_FETCH_TIMEOUT_MS) { getDisruptions(current).last() }) {
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
                    // Freshly captured here, not the `now` used for the first render above -- the
                    // disruptions fetch just awaited (up to DISRUPTIONS_FETCH_TIMEOUT_MS) may have
                    // taken real time, and this second render's own departure countdown must
                    // reflect the instant it is actually built at, not a timestamp from before
                    // that fetch even started.
                    val nowAfterDisruptionFetch = clock.instant()
                    routineNotifier.showOrUpdate(
                        RoutineNotificationMapper.map(notificationRoutine, departuresState, nowAfterDisruptionFetch, lastKnownDisruption?.toPresentation()),
                    )
                    // Mirrors the notification's own second, disruption-aware update above --
                    // same tick, same already-fetched departuresState, no separate widget fetch
                    // or timer.
                    runWidgetUpdateSafely { routineWidgetUpdater.updateWithDepartures(current, departuresState, nowAfterDisruptionFetch, lastKnownDisruption) }
                }

                // EXACT_DESTINATION's own second-phase disruption-relevance lookup -- mirrors the
                // LINE_DIRECTION block above's own primary-first/disruption-second timing. Sends
                // PRIMARY's own already-shown Journey Planner notices (the first post above)
                // ALONGSIDE its legs/origin site id so the backend's own single authoritative
                // resolver (see GetJourneyDisruptionRelevanceUseCase's own doc) can combine them
                // with structurally-matched cached SL Deviations -- never combined/deduplicated
                // here, since Journey Planner's own `infos` can miss a disruption entirely when it
                // silently reroutes PRIMARY around one without attaching any notice text at all
                // (confirmed live for Akalla -> Kungsträdgården). Bounded by the SAME
                // DISRUPTIONS_FETCH_TIMEOUT_MS and never awaited before the first post above -- a
                // timeout or failure here simply leaves this tick's own presentation
                // Journey-Planner-notices-only (still correct, just possibly incomplete), never
                // blocking or delaying the journey/countdown/notification/widget refresh already
                // completed above. No separate 30-second loop, no per-leg/per-stop upstream
                // request -- one bounded call per tick, reading the backend's own shared cached SL
                // Deviations snapshot (see `backend/src/routes/journeyDisruptions.ts`'s own doc).
                if (current.type == RoutineType.EXACT_DESTINATION && getJourneyDisruptionRelevance != null) {
                    val primary = journeyPlans.firstOrNull { it.role == JourneyRole.PRIMARY }
                    val resolvedDisruptions = if (primary != null) {
                        withTimeoutOrNull(DISRUPTIONS_FETCH_TIMEOUT_MS) {
                            try {
                                getJourneyDisruptionRelevance(primary.legs, current.siteId, journeyPlans.primaryDisruptionNotices())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    if (resolvedDisruptions.isNotEmpty()) {
                        // Already the backend's own fully resolved, deduplicated, merged result --
                        // renders exactly what was returned, no Android-side relevance inference.
                        val resolvedDisruption = resolvedDisruptions.compactPresentation()
                        // Only re-posts if the deviations lookup actually changed what's already
                        // shown -- mirrors the LINE_DIRECTION block above's own
                        // `lastKnownDisruption != disruptionAtPost` guard.
                        if (resolvedDisruption != exactDisruption) {
                            val nowAfterDeviationsFetch = clock.instant()
                            routineNotifier.showOrUpdate(
                                RoutineNotificationMapper.map(notificationRoutine, departuresState, nowAfterDeviationsFetch, resolvedDisruption),
                            )
                            runWidgetUpdateSafely {
                                routineWidgetUpdater.updateWithJourneys(
                                    current, journeyPlans, nowAfterDeviationsFetch,
                                    fetchFailed = rawJourneyPlans == null, disruption = resolvedDisruption,
                                )
                            }
                        }
                    }
                }

                // Subtracts however long this WHOLE tick actually took -- the routine re-read,
                // the departures fetch, caching, notification/widget rendering, and the
                // disruptions fetch above, not just the disruptions fetch alone -- from this
                // tick's own delay, rather than adding a flat ACTIVE_WINDOW_REFRESH_INTERVAL_MS
                // on top of however long that real work took. Without this, a slow departures
                // fetch (or slow rendering, or a slow-but-not-timed-out disruptions fetch, or any
                // combination) drifted total tick spacing to that work's own duration PLUS the
                // full 30s, rather than the intended ~30s from the start of one tick to the start
                // of the next (see ACTIVE_WINDOW_REFRESH_INTERVAL_MS's own doc). A tick whose own
                // work alone already consumed the full interval or more (coerceAtLeast(0L)) adds
                // no extra delay at all, rather than a negative one.
                val tickElapsedMs = (elapsedRealtimeProvider.elapsedRealtimeMillis() - tickStartElapsedRealtimeMillis).coerceAtLeast(0L)
                delay((ACTIVE_WINDOW_REFRESH_INTERVAL_MS - tickElapsedMs).coerceAtLeast(0L))
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
            // Ownership was already unconditionally claimed above, before setForeground was
            // even attempted -- so this cleanup always runs, whether the loop ran to completion,
            // broke early, or setForeground() itself threw before ever posting anything (see
            // this worker's own class doc on "if ownership succeeds but setForeground
            // subsequently fails, safely clean/reconcile that failed ownership/content state
            // using the existing mechanism" -- this IS that existing mechanism, not a separate
            // one). Ownership may still have been superseded by a REPLACEMENT run in the
            // meantime (e.g. editing this active routine cancelled this worker and immediately
            // started a new one, which claimed ownership for itself) -- only clean up if THIS
            // run is still the recorded owner of the content, so a cancelled/replaced run can
            // never clobber a newer run's own notification/widget content. The check itself must
            // run even while this coroutine is being cancelled (that's precisely the case it
            // exists to guard), so it's wrapped in NonCancellable; on any unexpected failure to
            // even determine ownership, this fails CLOSED (skips cleanup) rather than risking a
            // clear against unknown ownership -- see this worker's own class doc.
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
        }

        // This occurrence is over one way or another -- EXCEPT via the hard cap, its runtime
        // tracking is cleared so a genuinely NEW occurrence later never has to share space with
        // stale data (though its own different windowEnd would already make old state
        // irrelevant on its own -- see createFreshOccurrenceRuntimeState). Deliberately NOT
        // cleared when hitHardRuntimeCap is why this run stopped: this occurrence's window
        // (windowEnd) has NOT actually ended yet, only its real-elapsed-time budget has -- if a
        // replacement worker or reboot-recovered process ran again for this SAME still-open
        // occurrence and found no persisted state (because it had been cleared here), it would
        // treat it as fresh and grant a brand new allowance, defeating the whole cap. Leaving the
        // exhausted record in place lets doWork's own pre-foreground check (above) detect that
        // reliably instead -- the next GENUINE occurrence (a different windowEnd) replaces it the
        // normal way regardless, via createFreshOccurrenceRuntimeState's own upsert, so nothing
        // here is needed for that case either. Best-effort: a Room failure here must never
        // prevent the reschedule decision below from running.
        if (!hitHardRuntimeCap) {
            try {
                routineOccurrenceRuntimeRepository.clear(routineId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to clear occurrence runtime state for routine $routineId", e)
            }
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
     * against. Called once, as early as possible — BEFORE [setForeground]/any content is posted,
     * not after: claiming first is what guarantees an old run's own `finally` (which checks
     * [RoutineWorkOwnershipRepository.isOwner]) can never observe itself as "still the owner"
     * while this run's replacement content is on screen but not yet recorded as such. Required,
     * not best-effort: unlike most other cleanup/bookkeeping calls in this worker, a FAILED claim
     * here means this run has no confirmed right to the content it's about to post, so
     * [doWork] refuses to proceed to [setForeground] at all when this returns `false` — exactly
     * like the runtime-state read/write failures earlier in [doWork]. */
    private suspend fun claimContentOwnership(routineId: String): Boolean {
        return try {
            routineWorkOwnershipRepository.claim(routineId, id.toString())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to claim content ownership for routine $routineId; refusing to enter foreground execution", e)
            false
        }
    }

    /** Creates and DURABLY persists a FRESH [RoutineOccurrenceRuntimeState] for [routine]'s
     * occurrence identified by [windowEnd] — called only once [doWork]'s own pre-foreground read
     * has already confirmed no matching state exists yet to reuse instead. [windowStart] and
     * [windowEnd] are the two real instants [NextOccurrenceCalculator] resolved this occurrence's
     * own local start/end times to.
     *
     * The [effectiveHardCapMinutes] this occurrence should be measured against — ordinarily
     * [HARD_FOREGROUND_RUNTIME_CAP_MINUTES], but reduced when [routine]'s NEXT genuine occurrence
     * has been pulled closer by a daylight-saving transition (see that function's own doc) — is
     * encoded into BOTH persisted fields by recording a start shifted EARLIER than the true start
     * by exactly the reduction, rather than by adding a new field: [hasReachedHardRuntimeCap]
     * always compares against the same fixed [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] constant, so a
     * start shifted `reduction` minutes into the past makes that SAME fixed-constant comparison
     * fire exactly `effectiveHardCapMinutes` minutes after the TRUE start — on both the monotonic
     * and the reboot-fallback wall-clock path identically, since both are derived from this one
     * shifted reference point.
     *
     * Returns `null` if the write itself fails, rather than a state this run would otherwise use
     * in-memory-only: a fresh occurrence's safety record must be DURABLY persisted before
     * foreground execution begins, or a replacement worker or reboot-recovered process later,
     * finding nothing in Room for this routine, would wrongly treat this SAME occurrence as never
     * having started and grant it a second, fresh allowance on top of whatever real time this run
     * already used — exactly the "uncertainty manufactures extra foreground time" outcome this
     * whole mechanism exists to prevent (see [doWork]'s own pre-foreground READ failure handling
     * for the symmetric case, and its own comment at the call site here for how a `null` return is
     * handled: foreground execution is refused entirely, the same as an already-exhausted
     * occurrence). */
    private suspend fun createFreshOccurrenceRuntimeState(
        routine: CommuteRoutine,
        windowStart: ZonedDateTime,
        windowEnd: ZonedDateTime,
    ): RoutineOccurrenceRuntimeState? {
        val capMinutes = effectiveHardCapMinutes(routine, windowStart, windowEnd)
        val reductionMillis = Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES - capMinutes).toMillis()
        val trueStartElapsedRealtimeMillis = elapsedRealtimeProvider.elapsedRealtimeMillis()
        val trueStartInstant = clock.instant()

        val fresh = RoutineOccurrenceRuntimeState(
            occurrenceWindowEndEpochMilli = windowEnd.toInstant().toEpochMilli(),
            monotonicStartElapsedRealtimeMillis = trueStartElapsedRealtimeMillis - reductionMillis,
            bootCountAtStart = bootCountProvider.currentBootCount(),
            hardStopEpochMilli = trueStartInstant.minusMillis(reductionMillis)
                .plus(Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES))
                .toEpochMilli(),
        )
        return try {
            routineOccurrenceRuntimeRepository.save(routine.id, fresh)
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                LOG_TAG,
                "Failed to durably persist a fresh occurrence runtime state for routine " +
                    "${routine.id} before entering foreground; refusing to grant foreground time " +
                    "until this can be confirmed safe, rather than risking an in-memory-only " +
                    "budget a replacement worker or reboot could never see",
                e,
            )
            null
        }
    }

    /** Whether [state]'s occurrence has now run for [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] of REAL
     * elapsed time SINCE ITS RECORDED START — which [createFreshOccurrenceRuntimeState] may have
     * deliberately set earlier than the occurrence's true foreground-entry instant, to encode a
     * REDUCED effective cap using this same fixed constant (see that function's own doc); this
     * comparison itself never changes on either path below. While the device's boot count still
     * matches [RoutineOccurrenceRuntimeState.bootCountAtStart], [elapsedRealtimeProvider]
     * (monotonic, unaffected by wall-clock changes) is the authoritative measurement. Once the
     * boot count no longer matches, the device has rebooted since this occurrence started —
     * [android.os.SystemClock.elapsedRealtime] has been reset by the OS and comparing against
     * [RoutineOccurrenceRuntimeState.monotonicStartElapsedRealtimeMillis] directly would be
     * meaningless — so this falls back to [RoutineOccurrenceRuntimeState.hardStopEpochMilli], an
     * absolute wall-clock instant computed once when the occurrence first began (from that SAME,
     * possibly-shifted start), which survives a reboot precisely because it is NOT itself reset by
     * one. Either way, this never grants a fresh [HARD_FOREGROUND_RUNTIME_CAP_MINUTES] allowance
     * just because the worker (or the device) restarted. */
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

/** Projects up to the two most actionable journeys' own first public-transport legs into the
 * existing simple notification model. Final destination, competing journeys and final arrival
 * deliberately do not enter this projection. */
internal data class ExactJourneyNotificationProjection(
    val routine: CommuteRoutine,
    val departuresState: LiveDeparturesState,
)

/**
 * Returns `null` — refusing to project anything into a live notification at all — when [this]
 * contains no journey [isCurrentJourney] still considers current at [now] (already departed,
 * even by one millisecond, or over the two-change limit), rather than clamping a departure to
 * [now] to make an already-departed journey look current. That clamp was the root cause of a
 * real production incident: a bus that had already arrived (and a metro that had already arrived
 * after it) kept being shown as "FASTEST"/"alternative" with a "0 min" countdown well after both
 * had actually departed. The caller ([RoutineActiveWindowWorker.doWork]) already re-filters the
 * whole journey list against this exact same [now] before ever calling this function — see that
 * call site's own doc — but this check stays here too rather than trusting that upstream
 * guarantee alone, exactly like every other defensive check in this worker.
 *
 * Selects at most the first two still-current journeys, in [this]'s own order — the backend
 * already places them in PRIMARY → ALTERNATIVE? → NEXT chronological order (see
 * backend/src/routes/journeys.ts's own doc), so WHICH two journeys are picked never needs to
 * inspect [se.blick.app.domain.model.JourneyRole] itself: normally PRIMARY + NEXT, or PRIMARY +
 * ALTERNATIVE during a qualifying gap. Each selected journey's own real role IS still carried
 * along via [JourneyPlan.role] -> [PreparedDeparture.journeyRole] though (see
 * [toPreparedDeparture]) — [se.blick.app.notification.RoutineNotificationBuilder]'s own second
 * row wording (NEXT vs ALTERNATIVE) depends on it, even though the SELECTION here remains purely
 * positional.
 */
internal fun List<JourneyPlan>.toExactJourneyNotificationProjection(
    routine: CommuteRoutine,
    now: Instant,
): ExactJourneyNotificationProjection? {
    val current = filter { it.isCurrentJourney(now) }
    val primary = current.firstOrNull() ?: return null
    val departures = current.take(2).map { it.toPreparedDeparture(now) }
    return ExactJourneyNotificationProjection(
        routine = routine.copy(
            lineDesignation = primary.firstLeg.lineDesignation,
            transportMode = primary.firstLeg.transportMode,
            destinationLabel = primary.firstLeg.direction ?: primary.firstLeg.destinationName,
        ),
        departuresState = LiveDeparturesState.Live(LiveDeparturesSnapshot(departures, now)),
    )
}

private fun JourneyPlan.toPreparedDeparture(now: Instant): PreparedDeparture {
    val firstLegDeparture = effectiveFirstDeparture()
    return PreparedDeparture(
        departureId = journeyId,
        lineDesignation = firstLeg.lineDesignation.orEmpty(),
        direction = firstLeg.direction,
        destination = firstLeg.direction ?: firstLeg.destinationName,
        scheduledTime = firstLegDeparture,
        expectedTime = if (firstLeg.isRealtime) firstLegDeparture else null,
        effectiveTime = firstLegDeparture,
        // countdownMinutes, never a floor-based Duration.toMinutes().coerceAtLeast(0) -- see
        // that function's own doc for the ceiling rule this depends on (a departure under a
        // minute away must read "1 min", never floored to "0 min", while one exactly at `now`
        // reads "0 min"). No coerceAtLeast(0) is needed here to hide a negative duration: the
        // caller already filters to only still-current journeys before this is ever called.
        minutesRemaining = countdownMinutes(now, firstLegDeparture),
        isRealTime = firstLeg.isRealtime,
        isCancelled = false,
        state = "EXPECTED",
        journeyState = "NORMAL",
        predictionState = null,
        tripDeviations = emptyList(),
        journeyRole = role,
    )
}
