package se.blick.app.scheduling

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.RecoveryPendingStateStore
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import se.blick.app.billing.FreePremiumEntitlementRepository
import se.blick.app.billing.FreeRoutineSelectionStore
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.RoutineTierPolicy
import se.blick.app.domain.usecase.RoutineScheduleOverlapValidator

private const val LOG_TAG = "NotificationRecovery"

/** The narrow surface [RoutineActiveWindowWorker] and `RoutineDetailsViewModel` actually need
 * from [NotificationRecoveryCoordinator] — reporting an observed unavailable state, nothing
 * more. Kept as its own interface (rather than having those classes depend on the concrete
 * coordinator directly) so tests can substitute a lightweight fake instead of constructing the
 * real coordinator and all of ITS OWN dependencies just to verify a call was made. */
interface NotificationRecoveryReporter {
    /** Durably records that a notification-recovery attempt is owed. See
     * [NotificationRecoveryCoordinator.reportUnavailable]'s own doc for the full contract
     * (best-effort, rethrows [kotlinx.coroutines.CancellationException] unconverted). */
    suspend fun reportUnavailable()
}

/**
 * The sole authority for startup reconciliation, timezone-change reconciliation, and
 * notification-availability recovery — replaces `BlickApplication`'s separate cold-start
 * `RoutineScheduleReconciler.reconcileAll` launch, its formerly-separate
 * `Intent.ACTION_TIMEZONE_CHANGED` receiver call, `BootCompletedReceiver`'s own now-removed direct
 * reconciliation call (see this class's own [onAppStart] doc for why a dedicated boot path turned
 * out to be redundant with it), and the earlier `ForegroundNotificationRecovery` class. All three
 * remaining call sites ([onAppStart] from `BlickApplication.onCreate`, [onForeground] from its
 * `ProcessLifecycleOwner` `ON_START` observer, [onTimeZoneChanged] from its
 * `Intent.ACTION_TIMEZONE_CHANGED` receiver) now funnel through this one class, serialized by
 * [mutex] so overlapping or rapid-repeat calls (the first two triggers genuinely do overlap on
 * cold start — `ON_START` fires once at process start too — and a timezone change could in
 * principle land mid-recovery) can never interleave and schedule the same routine twice: whichever
 * call is second simply observes whatever the first one already left in place (an already-`RUNNING`
 * worker, or an already-cleared [RecoveryPendingStateStore]) and acts accordingly, rather than
 * racing it. [reportUnavailable] — called from entirely separate coroutines
 * ([RoutineActiveWindowWorker]'s own `doWork`, `RoutineDetailsViewModel`'s `viewModelScope`) that
 * never otherwise interact with this class — also serializes through the same [mutex], so a
 * freshly-reported "recovery is owed" signal can never land in the middle of, and be silently
 * discarded by, an in-progress [attemptPendingRecoveryIfNeeded]'s own unconditional clear (see
 * [reportUnavailable]'s own doc for the exact race this closes).
 *
 * **Startup/foreground recovery vs. timezone reconciliation are deliberately NOT the same
 * operation**, even though both now go through this one class for serialization. [onAppStart] and
 * [onForeground] both respect [RoutineScheduler.isActivationRunning] (see [scheduleIfSafe]) —
 * correct for them, since nothing about a routine's OWN correctness changed merely because the
 * process (re)started or came to the foreground; an already-`RUNNING` worker is already exactly
 * where it should be. A genuine device timezone change is different: the routine's configured
 * [java.time.LocalTime] start/end must now be reinterpreted against the NEW zone, so
 * [onTimeZoneChanged] deliberately calls [routineScheduleReconciler]'s own unconditional
 * `reconcileAll` — which always replaces, even a currently-`RUNNING` worker — rather than
 * [scheduleIfSafe]'s guarded version. Replacing a running worker here is correct, not merely
 * tolerated: the replacement immediately re-enters `doWork`, which recomputes its own `windowEnd`
 * fresh against the current zone, exactly like any other freshly-started run.
 *
 * **Durable pending-recovery, not a compared transition.** [RecoveryPendingStateStore] (see its
 * own doc) is a plain sticky "a recovery attempt is owed" flag, not a last-known-availability
 * snapshot compared against the current one. Every real detector of "notifications are NOT
 * available right now" — [RoutineActiveWindowWorker] before it stops for that reason,
 * `RoutineDetailsViewModel`'s own availability checks, and this class's own
 * [recordCurrentAvailabilityIfUnavailable] — durably marks it (via [reportUnavailable] or its
 * internal, already-locked equivalent [markRecoveryPendingSafely]). Availability later being
 * observed as available again does NOT itself clear it — only
 * [attemptPendingRecoveryIfNeeded] actually succeeding (every routine that needed it got
 * (re)scheduled, and the widget was reconciled) does. If Room, DataStore, or WorkManager throws
 * partway through that attempt, the flag is left set so the next foreground or startup check
 * retries — see that function's own `catch` block. A fresh install (nothing ever recorded)
 * defaults to `false`, so there is no transition to spuriously invent on first cold start; that
 * case is already covered unconditionally by [onAppStart]'s own reconciliation regardless of
 * this flag.
 *
 * **Recovery is deliberately narrower than [onAppStart]'s reconciliation.** [onAppStart] mirrors
 * [RoutineScheduleReconciler.reconcileAll]'s own "touch every enabled routine" behavior (a
 * process just started; nothing meaningful can already be `RUNNING` for it yet in a way worth
 * protecting), except it also respects [RoutineScheduler.isActivationRunning] for the
 * [NextOccurrence.ActiveNow] case, as a defensive, uniform safety net.
 * [attemptPendingRecoveryIfNeeded], on the other hand, only ever touches a routine whose active
 * window is open RIGHT NOW, and even then only if [RoutineScheduler.isActivationRunning] reports
 * nothing already running for it — an already-`RUNNING` worker is left completely untouched:
 * never re-enqueued, never replaced, its work id and state undisturbed. A routine outside its
 * active window is left alone entirely by this path — its existing future schedule is already
 * correct, and resuming a MISSED active window is this path's only job, not general
 * reconciliation (that stays [onAppStart]'s — and, for a live device timezone change,
 * [RoutineScheduleReconciler.reconcileAll]'s own, unchanged — job).
 *
 * **The check-then-enqueue race.** Neither [scheduleIfSafe] call site is perfectly atomic —
 * WorkManager exposes no atomic "replace unless running" primitive — so there is an unavoidable,
 * narrow race where a worker transitions from not-yet-started to actually `RUNNING` in the brief
 * window between [RoutineScheduler.isActivationRunning]'s query and [RoutineScheduler.scheduleActivation]
 * itself. That residual window is vanishingly narrow compared to the bug this class's
 * predecessor fixed (which fired on literally every foreground, unconditionally) — the same
 * class of best-effort race every other non-exact WorkManager scheduling call in this codebase
 * already accepts (see [WorkManagerRoutineScheduler]'s own doc on ordinary Doze/App-Standby
 * deferral).
 */
@Singleton
class NotificationRecoveryCoordinator @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val recoveryPendingStore: RecoveryPendingStateStore,
    private val routineScheduleReconciler: RoutineScheduleReconciler,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
    private val entitlementRepository: PremiumEntitlementRepository = FreePremiumEntitlementRepository,
    private val freeRoutineSelectionStore: FreeRoutineSelectionStore? = null,
) : NotificationRecoveryReporter {
    private val mutex = Mutex()

    /** Called once from `BlickApplication.onCreate` — covers reboot, an app update, and
     * ordinary process recreation, exactly like [RoutineScheduleReconciler.reconcileAll] always
     * has (WorkManager itself already persists enqueued work across all of these; this is a
     * defensive backstop, not the primary scheduling path).
     *
     * This is also, deliberately, the ONLY reconciliation reboot triggers — there is no separate
     * `BootCompletedReceiver`-style path anymore. `Application.onCreate` always runs before any
     * component (a `BroadcastReceiver` included) executes in a freshly-started process, so a
     * `BOOT_COMPLETED` broadcast that starts Blick's process at all (whether via Blick's own
     * receiver or, as it does today, purely via WorkManager's own bundled boot receiver, which
     * independently re-establishes its persisted work's system-level scheduling regardless of
     * anything Blick's own manifest declares) already reaches this exact method, through this
     * exact [mutex], before anything else in the process runs. A dedicated Blick-specific boot
     * path added nothing except a second, UNcoordinated caller of
     * [RoutineScheduleReconciler.reconcileAll] racing this one — that call has no
     * [RoutineScheduler.isActivationRunning] guard at all (see [onTimeZoneChanged]'s own doc on
     * why it deliberately doesn't), so it could `REPLACE` (cancelling and restarting) a worker
     * this method, or a WorkManager-triggered run that started first, had already correctly left
     * alone.
     *
     * **A genuinely successful reconciliation here satisfies [RecoveryPendingStateStore] too.**
     * If [recoveryPendingStore] was already left `true` by an earlier session (e.g. a worker's
     * own [se.blick.app.scheduling.RoutineActiveWindowWorker] discovered notifications
     * unavailable right before the process died), this reconciliation already just did
     * everything [attemptPendingRecoveryIfNeeded] would otherwise redo on the very next
     * [onForeground] — reconciling every enabled routine (not merely the narrower `ActiveNow`
     * subset that recovery itself is scoped to) and, via [scheduleIfSafe], never touching an
     * already-`RUNNING` worker. Without clearing the flag here, that next [onForeground] would
     * see `recoveryPending` still `true` and redundantly call [scheduleIfSafe] again for every
     * `ActiveNow` routine — including one this very call just enqueued (`ENQUEUED`, not yet
     * `RUNNING`, since WorkManager hasn't picked it up yet), which [scheduleIfSafe]'s
     * `isActivationRunning` guard does NOT protect (it only ever skips a worker that is already
     * genuinely `RUNNING`), so the redundant call would needlessly `REPLACE` it with an
     * equivalent new [androidx.work.WorkRequest] moments after this one already scheduled it
     * correctly. Only cleared when reconciliation was genuinely complete (every routine's
     * [se.blick.app.scheduling.RoutineScheduler.scheduleActivation] call succeeded, and the
     * routine list itself was read successfully — see [reconcileAllRoutinesUnconditionally]'s
     * own return value) AND notifications are currently available — otherwise the flag is left
     * exactly as it was, so [onForeground]'s own [attemptPendingRecoveryIfNeeded] still retries
     * later exactly as before. */
    suspend fun onAppStart() {
        mutex.withLock {
            val reconciledSuccessfully = reconcileAllRoutinesUnconditionally()
            recordCurrentAvailabilityIfUnavailable()
            if (reconciledSuccessfully && notificationAvailabilityChecker.check() == NotificationAvailability.Available) {
                clearRecoveryPendingSafely()
            }
        }
    }

    /** Called every time the app returns to the foreground (`ProcessLifecycleOwner`'s
     * `ON_START`). Cheap and a no-op on the overwhelmingly common case: nothing pending. */
    suspend fun onForeground() {
        mutex.withLock {
            recordCurrentAvailabilityIfUnavailable()
            attemptPendingRecoveryIfNeeded()
        }
    }

    /** Called from `BlickApplication`'s `Intent.ACTION_TIMEZONE_CHANGED` receiver — a live device
     * timezone change that an already-enqueued `WorkRequest`'s fixed `initialDelay` cannot
     * retroactively account for (see [RoutineScheduleReconciler]'s own doc). Delegates to
     * [routineScheduleReconciler]'s own, unchanged `reconcileAll` — deliberately NOT
     * [reconcileAllRoutinesUnconditionally]/[scheduleIfSafe]'s guarded version used by [onAppStart]
     * and [attemptPendingRecoveryIfNeeded] — see this class's own doc for why an already-`RUNNING`
     * worker must still be replaced here, unlike those two callers. Serialized through the same
     * [mutex] purely so this can never interleave with [onAppStart]/[onForeground]/
     * [reportUnavailable] and leave either side's own decision racing the other's — it does NOT
     * change what `reconcileAll` itself does. */
    suspend fun onTimeZoneChanged() {
        mutex.withLock {
            routineScheduleReconciler.reconcileAll()
        }
    }

    /** Durably records that a notification-recovery attempt is owed. Called by any observer
     * that directly detects notifications are NOT available right now
     * ([RoutineActiveWindowWorker], `RoutineDetailsViewModel`) — reporting is all such callers
     * do; only [onForeground]/[onAppStart] ever schedule anything. Best-effort: a
     * Room/DataStore failure here must never crash whichever caller detected the unavailable
     * state (a worker mid-`doWork`, or a ViewModel with no default exception handler on its
     * `viewModelScope`), but a genuine [CancellationException] is always rethrown unconverted.
     *
     * Acquires [mutex] itself — unlike [onAppStart]/[onForeground], this is called from
     * completely separate coroutines ([RoutineActiveWindowWorker]'s own `doWork`,
     * `RoutineDetailsViewModel`'s `viewModelScope`) that never otherwise touch this class, so
     * without this lock a worker's mark here could land in the middle of
     * [attemptPendingRecoveryIfNeeded]'s own read-act-clear sequence and be silently wiped out
     * by that sequence's own unconditional [RecoveryPendingStateStore.clearRecoveryPending] —
     * losing a genuinely fresh "recovery is owed" signal reported after the in-progress attempt
     * had already decided everything was handled. [recordCurrentAvailabilityIfUnavailable] is
     * the one internal caller that already holds this lock (from inside [onAppStart]/
     * [onForeground]), so it calls [markRecoveryPendingSafely] directly instead of this method,
     * to avoid self-deadlocking on a non-reentrant [Mutex]. */
    override suspend fun reportUnavailable() {
        mutex.withLock { markRecoveryPendingSafely() }
    }

    private suspend fun recordCurrentAvailabilityIfUnavailable() {
        if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) {
            markRecoveryPendingSafely()
        }
    }

    private suspend fun markRecoveryPendingSafely() {
        try {
            recoveryPendingStore.markRecoveryPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to durably record a pending notification recovery; a future check will retry", e)
        }
    }

    /** The [onAppStart] counterpart to [markRecoveryPendingSafely] — same fail-open shape
     * (log and leave the durable flag exactly as it was on failure) so a
     * [RecoveryPendingStateStore] write failure here can never crash [onAppStart], and can
     * never be mistaken for "recovery genuinely satisfied": if this throws, `recoveryPending`
     * simply stays whatever it already was, so [onForeground]'s own retry is unaffected. */
    private suspend fun clearRecoveryPendingSafely() {
        try {
            recoveryPendingStore.clearRecoveryPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to durably clear a satisfied pending notification recovery; a future check will retry it redundantly", e)
        }
    }

    /** Reading the routine list itself, and reconciling each one, are both wrapped in their own
     * try/catch (a genuine [CancellationException] always rethrows unconverted) so a Room read
     * failure, a corrupted routine record, or a WorkManager call throwing on some OEM can't
     * propagate uncaught up through [onAppStart] to crash the app on cold start, and can't abort
     * the `forEach` and silently leave every routine after the failing one in iteration order
     * unreconciled too. Mirrors [attemptPendingRecoveryIfNeeded]'s own catch around the
     * equivalent calls, which this function's own lack of one (before this) did not.
     *
     * Returns whether every routine was genuinely reconciled — `false` if the routine list
     * itself failed to read, or if any individual routine's [scheduleIfSafe] call failed — so
     * [onAppStart] can tell a genuinely complete pass from a partial one before deciding whether
     * satisfying [RecoveryPendingStateStore]'s pending flag is warranted. A routine's own widget
     * reconciliation failure below (wrapped in [runWidgetUpdateSafely]) deliberately does NOT
     * affect this return value — the widget is a best-effort, secondary concern, unconditionally
     * attempted either way, exactly like [attemptPendingRecoveryIfNeeded]'s own widget call
     * unconditionally precedes its `clearRecoveryPending()`. */
    private suspend fun reconcileAllRoutinesUnconditionally(): Boolean {
        val now = zonedNow()
        var allSucceeded = true
        val routines = try {
            routineRepository.observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read the routine list at startup; reconciling nothing this pass", e)
            allSucceeded = false
            emptyList()
        }
        val safeRoutines = if (freeRoutineSelectionStore != null) {
            RoutineScheduleOverlapValidator.nonOverlapping(routines)
        } else {
            routines
        }
        routines.forEach { routine ->
            if (!routine.enabled) return@forEach
            if (safeRoutines.none { it.id == routine.id }) {
                routineScheduler.cancelActivation(routine.id)
                return@forEach
            }
            if (freeRoutineSelectionStore != null && !RoutineTierPolicy.canRun(
                    routine, routines, entitlementRepository.entitlement.value,
                    freeRoutineSelectionStore.selectedRoutineId.value,
                )) {
                routineScheduler.cancelActivation(routine.id)
                return@forEach
            }
            try {
                val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, now, excludedDate = routine.pausedDate)
                scheduleIfSafe(routine, occurrence)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to reconcile routine ${routine.id} at startup; continuing with the rest", e)
                allSucceeded = false
            }
        }
        runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
        return allSucceeded
    }

    /** Only ever acts when [RecoveryPendingStateStore.recoveryPending] is currently `true` AND
     * notifications are available right now — otherwise a no-op, so repeated ordinary
     * foreground events cost nothing. Clears the pending flag ONLY after every routine that
     * needed (re)scheduling actually succeeded and the widget was reconciled; any failure
     * (Room, DataStore, or WorkManager) leaves it set so the very next foreground or startup
     * check retries from scratch, rather than silently losing the recovery. */
    private suspend fun attemptPendingRecoveryIfNeeded() {
        if (!recoveryPendingStore.recoveryPending.first()) return
        if (notificationAvailabilityChecker.check() != NotificationAvailability.Available) return

        try {
            val now = zonedNow()
            val routines = routineRepository.observeAll().first()
            val safeRoutines = if (freeRoutineSelectionStore != null) {
                RoutineScheduleOverlapValidator.nonOverlapping(routines)
            } else {
                routines
            }
            routines.forEach { routine ->
                if (!routine.enabled) return@forEach
                if (safeRoutines.none { it.id == routine.id }) return@forEach
                if (freeRoutineSelectionStore != null && !RoutineTierPolicy.canRun(
                        routine, routines, entitlementRepository.entitlement.value,
                        freeRoutineSelectionStore.selectedRoutineId.value,
                    )) return@forEach
                val occurrence = NextOccurrenceCalculator.nextOccurrence(routine, now, excludedDate = routine.pausedDate)
                if (occurrence is NextOccurrence.ActiveNow) {
                    scheduleIfSafe(routine, occurrence)
                }
                // Outside the active window: left alone entirely -- its existing future
                // schedule is already correct, and this path's whole purpose is resuming a
                // MISSED active window, not general reconciliation.
            }
            runWidgetUpdateSafely { routineWidgetUpdater.reconcile() }
            recoveryPendingStore.clearRecoveryPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Notification recovery attempt failed; leaving it pending for the next foreground/startup check", e)
        }
    }

    /** The one place either [reconcileAllRoutinesUnconditionally] or
     * [attemptPendingRecoveryIfNeeded] actually calls [RoutineScheduler.scheduleActivation] —
     * skips it entirely when [occurrence] is [NextOccurrence.ActiveNow] and a worker is already
     * `RUNNING` for [routine], since `scheduleActivation`'s `ExistingWorkPolicy.REPLACE` would
     * otherwise cancel and replace it. Every other case (no occurrence, an upcoming one, or an
     * active one with nothing currently running) is safe to schedule unconditionally. */
    private suspend fun scheduleIfSafe(routine: CommuteRoutine, occurrence: NextOccurrence) {
        if (occurrence is NextOccurrence.ActiveNow && routineScheduler.isActivationRunning(routine.id)) return
        routineScheduler.scheduleActivation(routine)
    }

    private fun zonedNow(): ZonedDateTime = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())
}
