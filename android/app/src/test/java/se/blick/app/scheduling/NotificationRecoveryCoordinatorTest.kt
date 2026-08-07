package se.blick.app.scheduling

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.RoutineDurationValidationResult
import se.blick.app.domain.usecase.RoutineDurationValidator
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.RecoveryPendingStateStore
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises [NotificationRecoveryCoordinator] directly — the same two methods
 * ([NotificationRecoveryCoordinator.onAppStart], [NotificationRecoveryCoordinator.onForeground])
 * `BlickApplication.onCreate`/its `ProcessLifecycleOwner` `ON_START` observer call unchanged, so
 * testing them here IS testing those two application-level call sites, without the Hilt/Android
 * process boilerplate around them — the same convention every other coordinator-style class in
 * this codebase already follows (see the deleted `ForegroundNotificationRecoveryTest`, which this
 * file's real-WorkManager tests are adapted from). `RoutineDetailsViewModelTest`'s "reports
 * unavailable, never schedules" tests and `RoutineActiveWindowWorkerTest`'s
 * `reportUnavailableCallCount` assertions cover this coordinator's other two real callers.
 *
 * Most tests below use lightweight in-memory fakes for [RoutineScheduler] — sufficient for
 * proving the coordinator's OWN serialization/retry/pending-clearing logic. The tests
 * specifically about an already-`RUNNING` worker surviving a foreground/startup call use a REAL
 * (in-memory, real-background-executor) [WorkManager] instance instead, since only a genuine
 * [WorkInfo.State.RUNNING] can prove [RoutineScheduler.isActivationRunning] actually prevents a
 * second call site from replacing it — a fake can't reproduce that race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationRecoveryCoordinatorTest {

    // Monday 2026-07-27, 07:30 Europe/Stockholm (CEST, UTC+2) -- INSIDE the default routine()'s
    // 07:00-09:00 window (matching RoutineActiveWindowWorkerTest/the deleted
    // ForegroundNotificationRecoveryTest's own fixtures).
    private val now: Instant = Instant.parse("2026-07-27T05:30:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    // Same Monday, 05:00 Stockholm -- BEFORE the window opens.
    private val earlierNow: Instant = Instant.parse("2026-07-27T03:00:00Z")
    private val earlierClock: Clock = Clock.fixed(earlierNow, ZoneOffset.UTC)

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val zoneProvider = DeviceZoneProvider { zone }

    private fun routine(id: String = "r1", pausedDate: LocalDate? = null) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        enabled = true,
        pausedDate = pausedDate,
    )

    // ---- Fakes shared by the coordinator-only (non-WorkManager) tests ----

    private class FakeRoutineRepository(initial: List<CommuteRoutine>) : RoutineRepository {
        private val state = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<CommuteRoutine>> = state
        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: LocalDate) = throw NotImplementedError()
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = state.value.isNotEmpty()
    }

    /** Like [FakeRoutineRepository], but [observeAll] can be paused mid-collection via
     * [pauseNextObserveAll] — used to force two concurrent [NotificationRecoveryCoordinator]
     * calls to genuinely overlap in wall-clock terms, so the coordinator's own [kotlinx.coroutines.sync.Mutex]
     * (not merely single-threaded test-dispatcher ordering) is what's actually under test. */
    private class ControllableRoutineRepository(initial: List<CommuteRoutine>) : RoutineRepository {
        private val state = MutableStateFlow(initial)
        private var gate: CompletableDeferred<Unit>? = null
        var observeAllCallCount = 0
            private set

        fun pauseNextObserveAll(): CompletableDeferred<Unit> {
            val deferred = CompletableDeferred<Unit>()
            gate = deferred
            return deferred
        }

        override fun observeAll(): Flow<List<CommuteRoutine>> = flow {
            observeAllCallCount++
            gate?.let { pending -> pending.await(); gate = null }
            emit(state.value)
        }
        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: LocalDate) = throw NotImplementedError()
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = state.value.isNotEmpty()
    }

    /** Records every [scheduleActivation] call; [failNextSchedulesCount] lets a test simulate a
     * transient WorkManager/Room failure on the next N calls before succeeding, and
     * [throwCancellation] simulates a genuine coroutine cancellation instead of an ordinary
     * failure — see [RecoveryPendingStateStore]'s own doc on why these must be handled
     * differently. */
    private class RecordingRoutineScheduler(
        private val isRunning: Boolean = false,
        private var failNextSchedulesCount: Int = 0,
        private val throwCancellation: Boolean = false,
    ) : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            if (throwCancellation) throw CancellationException("simulated cancellation")
            if (failNextSchedulesCount > 0) {
                failNextSchedulesCount--
                throw RuntimeException("simulated WorkManager failure")
            }
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = isRunning
    }

    private class FakeNotificationAvailabilityChecker(
        private val available: Boolean,
    ) : NotificationAvailabilityChecker {
        override fun check(): NotificationAvailability =
            if (available) NotificationAvailability.Available else NotificationAvailability.AppDisabled
    }

    private class InMemoryRecoveryPendingStateStore(initiallyPending: Boolean) : RecoveryPendingStateStore {
        private val state = MutableStateFlow(initiallyPending)
        override val recoveryPending: Flow<Boolean> = state
        override suspend fun markRecoveryPending() {
            state.value = true
        }
        override suspend fun clearRecoveryPending() {
            state.value = false
        }
    }

    /** Throws a genuine [CancellationException] from every write — for proving
     * [NotificationRecoveryCoordinator.reportUnavailable] rethrows it unconverted instead of
     * logging and swallowing it like an ordinary failure. */
    private class CancellingRecoveryPendingStateStore : RecoveryPendingStateStore {
        override val recoveryPending: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun markRecoveryPending(): Unit = throw CancellationException("simulated cancellation")
        override suspend fun clearRecoveryPending(): Unit = throw CancellationException("simulated cancellation")
    }

    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) = Unit
        override suspend fun clear() = Unit
        override suspend fun reconcile() {
            reconcileCallCount++
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    private fun buildCoordinator(
        repository: RoutineRepository,
        scheduler: RoutineScheduler,
        pendingStore: RecoveryPendingStateStore,
        available: Boolean,
        coordinatorClock: Clock = clock,
        widgetUpdater: RoutineWidgetUpdater = RecordingWidgetUpdater(),
        // Defaults to a REAL reconciler wired to the same fakes -- most tests here don't care
        // about onTimeZoneChanged at all; the dedicated tests for it override this with one
        // built against a gate/real WorkManager as needed.
        routineScheduleReconciler: RoutineScheduleReconciler = RoutineScheduleReconciler(repository, scheduler, widgetUpdater),
    ) = NotificationRecoveryCoordinator(
        routineRepository = repository,
        routineScheduler = scheduler,
        routineWidgetUpdater = widgetUpdater,
        notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(available),
        recoveryPendingStore = pendingStore,
        routineScheduleReconciler = routineScheduleReconciler,
        clock = coordinatorClock,
        deviceZoneProvider = zoneProvider,
    )

    // ---- 1. Serialized concurrent foreground callbacks schedule recovery exactly once ----

    @Test
    fun `concurrent onForeground calls are serialized so a pending recovery is scheduled only once`() = runTest {
        val routine = routine()
        val repository = ControllableRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler()
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        val gate = repository.pauseNextObserveAll()
        val job1 = launch { coordinator.onForeground() }
        runCurrent() // job1 acquires the Mutex, reaches observeAll(), suspends on the gate
        val job2 = launch { coordinator.onForeground() }
        runCurrent() // job2 must block on the Mutex itself -- it can't even reach observeAll() yet
        assertEquals(1, repository.observeAllCallCount)

        gate.complete(Unit)
        job1.join()
        job2.join()

        // job1 completed the whole recovery and cleared pending; job2, once it finally acquired
        // the Mutex, saw pending already false and returned immediately without scheduling again.
        assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
        assertEquals(1, repository.observeAllCallCount)
    }

    // ---- Regression: a report arriving mid-attempt must never be lost to that attempt's own
    // trailing clear (reportUnavailable is called from entirely separate coroutines -- workers,
    // ViewModels -- that don't otherwise touch this coordinator, so without reportUnavailable
    // itself acquiring the Mutex, a fresh mark could land between attemptPendingRecoveryIfNeeded's
    // own read and its unconditional clearRecoveryPending, and be silently wiped out) ----

    @Test
    fun `a report arriving while a recovery attempt is in progress is never lost to that attempt's own clear`() =
        runTest {
            val routineA = routine(id = "a")
            val repository = ControllableRoutineRepository(listOf(routineA))
            val scheduler = RecordingRoutineScheduler()
            val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
            val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

            val gate = repository.pauseNextObserveAll()
            val foregroundJob = launch { coordinator.onForeground() }
            runCurrent() // onForeground acquires the Mutex, reaches observeAll(), suspends on the gate

            // A worker for some OTHER routine discovers unavailability concurrently, mid-attempt
            // -- must not be silently discarded by this attempt's own trailing clear.
            val reportJob = launch { coordinator.reportUnavailable() }
            runCurrent() // reportJob must block on the Mutex itself, never racing the in-flight attempt

            gate.complete(Unit)
            foregroundJob.join()
            reportJob.join()

            assertEquals(
                "the concurrently-reported pending state must survive, not be clobbered by the " +
                    "in-flight attempt's own clear",
                true,
                pendingStore.recoveryPending.first(),
            )
        }

    // ---- onTimeZoneChanged is serialized against the other three entry points through the
    // SAME mutex, not a second lock ----

    @Test
    fun `onTimeZoneChanged is serialized against a concurrent onForeground call through the same mutex`() = runTest {
        val routine = routine()
        val repository = ControllableRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler()
        // Nothing pending: onForeground's own work (recordCurrentAvailabilityIfUnavailable +
        // attemptPendingRecoveryIfNeeded) would otherwise return near-instantly without ever
        // touching the repository -- exactly why this proves onForeground blocks on the MUTEX
        // itself, not merely on some unrelated I/O the two calls happen to share.
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val reconciler = RoutineScheduleReconciler(repository, scheduler, RecordingWidgetUpdater())
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true, routineScheduleReconciler = reconciler)

        val gate = repository.pauseNextObserveAll()
        val tzJob = launch { coordinator.onTimeZoneChanged() }
        runCurrent() // tzJob acquires the Mutex, reaches reconcileAll's own observeAll(), suspends on the gate
        val foregroundJob = launch { coordinator.onForeground() }
        runCurrent() // foregroundJob must block on the Mutex itself

        assertTrue(
            "onForeground must still be blocked on the Mutex while onTimeZoneChanged holds it -- " +
                "with nothing pending it would otherwise have already completed by now",
            foregroundJob.isActive,
        )

        gate.complete(Unit)
        tzJob.join()
        foregroundJob.join()

        // onTimeZoneChanged's own reconcileAll() genuinely ran once the gate released.
        assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
    }

    // ---- 5. Re-enabling notifications after a background disable->enable recovers the
    // missing worker ----

    @Test
    fun `re-enabling notifications after a background disable recovers the missing worker on the next foreground`() =
        runTest {
            val routine = routine()
            val repository = FakeRoutineRepository(listOf(routine))
            val scheduler = RecordingRoutineScheduler(isRunning = false)
            // Pending was already set true by an earlier reportUnavailable() call (the worker
            // itself, or RoutineDetailsViewModel, having observed the unavailable state).
            val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
            val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

            coordinator.onForeground()

            assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
            assertEquals(false, pendingStore.recoveryPending.first())
        }

    // ---- 6 & 7. A scheduling failure leaves pending set; the next foreground retries and only
    // then clears it ----

    @Test
    fun `a scheduling failure during recovery leaves the pending flag set`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler(failNextSchedulesCount = 1)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onForeground()

        assertTrue(scheduler.scheduledRoutines.isEmpty())
        assertEquals(true, pendingStore.recoveryPending.first())
    }

    @Test
    fun `the next foreground after a scheduling failure retries and only then clears the pending flag`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler(failNextSchedulesCount = 1)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onForeground() // fails -- see the test above
        assertEquals(true, pendingStore.recoveryPending.first())

        coordinator.onForeground() // retries -- scheduleActivation succeeds this time

        assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
        assertEquals(false, pendingStore.recoveryPending.first())
    }

    // ---- 8. Repeated foreground events after success do nothing further ----

    @Test
    fun `repeated foreground events after a successful recovery schedule nothing further`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler()
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onForeground()
        assertEquals(1, scheduler.scheduledRoutines.size)

        coordinator.onForeground()
        coordinator.onForeground()

        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    // ---- 10. Foregrounding outside an active window preserves the correct future schedule ----

    @Test
    fun `foregrounding outside an active window does not touch that routine, leaving its future schedule alone`() =
        runTest {
            // "now" is BEFORE the routine's window opens (see earlierClock) -- NextOccurrence is
            // Upcoming, not ActiveNow, so attemptPendingRecoveryIfNeeded must never call
            // scheduleActivation for it at all; its existing (real, out-of-band) future schedule
            // is left completely untouched.
            val routine = routine()
            val repository = FakeRoutineRepository(listOf(routine))
            val scheduler = RecordingRoutineScheduler()
            val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
            val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true, coordinatorClock = earlierClock)

            coordinator.onForeground()

            assertTrue(
                "a routine outside its active window must never be (re)scheduled by foreground recovery",
                scheduler.scheduledRoutines.isEmpty(),
            )
            // Still a fully successful attempt (nothing needed recovering) -- pending clears.
            assertEquals(false, pendingStore.recoveryPending.first())
        }

    // ---- onAppStart's own reconcileAllRoutinesUnconditionally: one routine's failure must not
    // abort reconciling the rest, and a routine-list read failure must not crash the app on
    // cold start -- neither was true before this behavior was added. ----

    @Test
    fun `onAppStart schedules every enabled routine even when an earlier one's scheduling throws`() = runTest {
        val routineA = routine(id = "a")
        val routineB = routine(id = "b")
        val routineC = routine(id = "c")
        val repository = FakeRoutineRepository(listOf(routineA, routineB, routineC))
        // Fails only the FIRST scheduleActivation call (routineA, in iteration order) --
        // proving b and c still get scheduled rather than the forEach aborting there.
        val scheduler = RecordingRoutineScheduler(failNextSchedulesCount = 1)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onAppStart()

        assertEquals(listOf("b", "c"), scheduler.scheduledRoutines.map { it.id })
    }

    @Test
    fun `onAppStart does not crash when reading the routine list fails`() = runTest {
        val repository = object : RoutineRepository {
            override fun observeAll(): Flow<List<CommuteRoutine>> = flow { throw RuntimeException("Room read failed") }
            override suspend fun getById(id: String): CommuteRoutine? = throw NotImplementedError()
            override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
            override suspend fun delete(id: String) = throw NotImplementedError()
            override suspend fun pauseForDate(id: String, date: LocalDate) = throw NotImplementedError()
            override suspend fun clearPause(id: String) = throw NotImplementedError()
            override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
            override suspend fun hasAnyRoutine(): Boolean = throw NotImplementedError()
        }
        val scheduler = RecordingRoutineScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val coordinator = buildCoordinator(
            repository, scheduler, InMemoryRecoveryPendingStateStore(initiallyPending = false), available = true,
            widgetUpdater = widgetUpdater,
        )

        val result = kotlin.runCatching { coordinator.onAppStart() }

        assertTrue("a routine-list read failure must not crash onAppStart", result.isSuccess)
        assertTrue(scheduler.scheduledRoutines.isEmpty())
        // Still runs -- a Room failure fetching the routine list is unrelated to whether the
        // widget itself needs reconciling.
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    // ---- Fix: a genuinely successful onAppStart reconciliation satisfies recoveryPending, so a
    // cold-start onForeground occurring right afterward (recoveryPending left true by an earlier
    // session) can never redundantly REPLACE a worker onAppStart already correctly ENQUEUED --
    // scheduleIfSafe's own isActivationRunning guard only ever protects an already-RUNNING
    // worker, never a merely-ENQUEUED one, so without this fix that immediate second call was
    // unprotected. ----

    @Test
    fun `a successful onAppStart reconciliation clears recoveryPending so a later onForeground does not replace the newly scheduled worker`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch)
        val realScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        // Simulates recoveryPending having already been left true by an earlier session (e.g. a
        // worker discovered notifications unavailable right before the process died) -- exactly
        // the state that used to make the very next onForeground() redundantly REPLACE whatever
        // onAppStart() had ALREADY correctly just scheduled.
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
        )

        coordinator.onAppStart()

        // Whichever state it's reached by now (ENQUEUED, or already RUNNING if the real
        // background executor happened to pick it up first) -- exactly one non-cancelled
        // WorkInfo exists, and a genuinely successful reconciliation must satisfy the pending
        // recovery immediately, before onForeground ever runs.
        val afterAppStart = workInfosFor(routine.id).single { it.state != WorkInfo.State.CANCELLED }
        assertEquals(
            "a fully successful reconciliation (available + every routine scheduled) must clear recoveryPending",
            false,
            pendingStore.recoveryPending.first(),
        )

        coordinator.onForeground()

        val afterForeground = workInfosFor(routine.id).single { it.state != WorkInfo.State.CANCELLED }
        assertEquals(
            "onForeground must never replace the WorkRequest onAppStart already correctly scheduled",
            afterAppStart.id,
            afterForeground.id,
        )
        assertEquals(false, pendingStore.recoveryPending.first())

        releaseLatch.countDown() // let the worker finish so it doesn't leak past this test
    }

    @Test
    fun `onAppStart does not clear recoveryPending when reconciliation succeeds but notifications are currently unavailable`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler() // never fails
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = false)

        coordinator.onAppStart()

        // Reconciliation itself succeeded (every routine scheduled without error) -- but
        // notifications are unavailable right now, so recovery is not actually satisfied yet.
        assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
        assertEquals(true, pendingStore.recoveryPending.first())
    }

    @Test
    fun `a failed onAppStart reconciliation leaves recoveryPending set so a later onForeground still retries`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        // Fails the FIRST scheduleActivation call (this routine, the only one, during
        // onAppStart's own reconciliation) -- proving an incomplete pass must not satisfy
        // recoveryPending; a later call (onForeground's own retry, once the transient failure
        // has cleared) succeeds normally.
        val scheduler = RecordingRoutineScheduler(failNextSchedulesCount = 1)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onAppStart()

        assertTrue(scheduler.scheduledRoutines.isEmpty())
        assertEquals(
            "an incomplete reconciliation must never satisfy recoveryPending -- it must stay set " +
                "for the next foreground/startup check to retry",
            true,
            pendingStore.recoveryPending.first(),
        )

        coordinator.onForeground()

        assertEquals(listOf(routine.id), scheduler.scheduledRoutines.map { it.id })
        assertEquals(false, pendingStore.recoveryPending.first())
    }

    // ---- Defensive daily-duration-limit re-check (see RoutineDurationValidator): onAppStart's
    // reconciliation must behave correctly when the scheduler it calls silently declines an
    // over-limit routine, exactly like the real WorkManagerRoutineScheduler does. ----

    /** Mirrors [WorkManagerRoutineScheduler]'s own defensive duration re-check using the SAME
     * shared [RoutineDurationValidator], without needing a real [WorkManager] instance for this
     * -- proves the coordinator's reconciliation continues past a silently-declined (no
     * exception, unlike [RecordingRoutineScheduler]'s `failNextSchedulesCount`) over-limit
     * routine exactly as it does past a throwing one. */
    private class DurationValidatingScheduler : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            val validation = RoutineDurationValidator.validateSelf(routine)
            if (validation is RoutineDurationValidationResult.ExceedsDailyLimit) return
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    @Test
    fun `onAppStart silently skips an over-limit routine without preventing the rest from being reconciled`() = runTest {
        val validBefore = routine(id = "a")
        val overLimit = routine(id = "b").copy(startTime = LocalTime.of(6, 0), endTime = LocalTime.of(13, 0)) // 7h
        val validAfter = routine(id = "c")
        val repository = FakeRoutineRepository(listOf(validBefore, overLimit, validAfter))
        val scheduler = DurationValidatingScheduler()
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        coordinator.onAppStart()

        assertEquals(listOf("a", "c"), scheduler.scheduledRoutines.map { it.id })
    }

    // ---- 14. Cancellation is always rethrown, never converted to an ordinary failure/success ----

    @Test
    fun `reportUnavailable rethrows a genuine CancellationException instead of swallowing it`() = runTest {
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(emptyList()),
            scheduler = RecordingRoutineScheduler(),
            pendingStore = CancellingRecoveryPendingStateStore(),
            available = true,
        )

        var caught: CancellationException? = null
        try {
            coordinator.reportUnavailable()
        } catch (e: CancellationException) {
            caught = e
        }
        assertTrue("expected reportUnavailable to rethrow a CancellationException, but it was swallowed", caught != null)
    }

    @Test
    fun `onForeground rethrows a genuine CancellationException from a failed scheduling attempt`() = runTest {
        val routine = routine()
        val repository = FakeRoutineRepository(listOf(routine))
        val scheduler = RecordingRoutineScheduler(throwCancellation = true)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(repository, scheduler, pendingStore, available = true)

        var caught: CancellationException? = null
        try {
            coordinator.onForeground()
        } catch (e: CancellationException) {
            caught = e
        }
        assertTrue("expected onForeground to rethrow a CancellationException, but it was swallowed", caught != null)
        // A real cancellation must never be misrepresented as either "recovery failed, retry
        // later" or "recovery succeeded" -- pending is left exactly as it was.
        assertEquals(true, pendingStore.recoveryPending.first())
    }

    // ==== Tests requiring a REAL WorkManager instance (RUNNING-work behavior) ====
    //
    // Adapted from the deleted ForegroundNotificationRecoveryTest -- a genuine background
    // executor (not androidx.work.testing.SynchronousExecutor) lets a worker actually reach and
    // stay in WorkInfo.State.RUNNING while the test thread concurrently drives the coordinator,
    // which a hand-rolled RoutineScheduler fake cannot reproduce.

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private var nextWorkerBehavior: WorkerBehavior = WorkerBehavior.Instant

    private sealed interface WorkerBehavior {
        data object Instant : WorkerBehavior
        data class Blocking(val startedLatch: CountDownLatch, val releaseLatch: CountDownLatch) : WorkerBehavior
    }

    private fun setUpRealWorkManager() {
        context = RuntimeEnvironment.getApplication()
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != RoutineActiveWindowWorker::class.java.name) return null
                return when (val behavior = nextWorkerBehavior) {
                    is WorkerBehavior.Instant -> object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = Result.success()
                    }
                    is WorkerBehavior.Blocking -> object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result {
                            behavior.startedLatch.countDown()
                            behavior.releaseLatch.await()
                            return Result.success()
                        }
                    }
                }
            }
        }
        val config = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setExecutor(Executors.newCachedThreadPool())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun workInfosFor(routineId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerRoutineScheduler.uniqueWorkName(routineId)).get()

    // ---- 2 & 9. onAppStart and onForeground colliding on the same already-RUNNING work never
    // double-schedule it, and its WorkManager id is left completely unchanged ----

    @Test
    fun `onAppStart starting a worker and a later onForeground call never double-schedule or replace it`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch)
        val realScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
        )

        // onAppStart's own unconditional reconciliation is what starts the worker the first time.
        coordinator.onAppStart()
        assertTrue("worker never started running", startedLatch.await(5, TimeUnit.SECONDS))
        val runningBefore = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }

        // A foreground event arrives while that worker is still genuinely running -- recovery is
        // still marked pending, so without the isActivationRunning guard this would replace it.
        coordinator.onForeground()

        val runningAfter = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }
        assertEquals("the already-running worker's own work id must be unchanged", runningBefore.id, runningAfter.id)

        releaseLatch.countDown() // let the worker finish so it doesn't leak past this test
    }

    // ---- Boot-race regression: a worker already RUNNING before onAppStart ever gets a chance
    // to run (simulating WorkManager's own bundled boot receiver having already re-established
    // this routine's scheduling and started it) is not replaced. This is the scenario a
    // dedicated, unguarded BootCompletedReceiver-style reconcileAll() call used to race --
    // removing that duplicate path means boot now reduces to exactly this already-guarded case. ----

    @Test
    fun `a worker already RUNNING before onAppStart runs at all is not replaced by it`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch)
        val realScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)

        // Stands in for WorkManager's own bundled boot receiver independently re-establishing
        // this routine's scheduling and starting it -- entirely before this test's coordinator
        // is even constructed, let alone run.
        realScheduler.scheduleActivation(routine)
        assertTrue("worker never started running", startedLatch.await(5, TimeUnit.SECONDS))
        val runningBefore = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }

        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
        )

        coordinator.onAppStart()

        val runningAfter = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }
        assertEquals("the already-running worker's own work id must be unchanged", runningBefore.id, runningAfter.id)

        releaseLatch.countDown() // let the worker finish so it doesn't leak past this test
    }

    // ---- onTimeZoneChanged: unlike onAppStart/onForeground above, it deliberately DOES replace
    // an already-RUNNING worker, and correctly reschedules an Upcoming routine with a real delay ----

    @Test
    fun `onTimeZoneChanged replaces an already-RUNNING worker, unlike onAppStart or onForeground`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        val firstStartedLatch = CountDownLatch(1)
        val firstReleaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(firstStartedLatch, firstReleaseLatch)
        val realScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)

        realScheduler.scheduleActivation(routine)
        assertTrue("worker never started running", firstStartedLatch.await(5, TimeUnit.SECONDS))
        val runningBefore = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }

        // A second, separately-latched behavior for the REPLACEMENT worker -- once IT starts,
        // the original work id can no longer be the one RUNNING (WorkManager only ever runs one
        // worker at a time for the same unique work name), so waiting for this to start is
        // definitive proof the replacement genuinely took effect, not a race against this test's
        // own assertions.
        val secondStartedLatch = CountDownLatch(1)
        val secondReleaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(secondStartedLatch, secondReleaseLatch)
        val realReconciler = RoutineScheduleReconciler(FakeRoutineRepository(listOf(routine)), realScheduler, RecordingWidgetUpdater())
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
            routineScheduleReconciler = realReconciler,
        )

        coordinator.onTimeZoneChanged()

        assertTrue("the replacement worker never started running", secondStartedLatch.await(5, TimeUnit.SECONDS))
        val runningAfter = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }
        assertTrue(
            "expected a genuinely NEW work id to now own this unique work, not the original running one",
            runningAfter.id != runningBefore.id,
        )

        firstReleaseLatch.countDown()
        secondReleaseLatch.countDown()
    }

    @Test
    fun `onTimeZoneChanged reschedules an Upcoming routine with a real delay, not a zero-delay loop`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        nextWorkerBehavior = WorkerBehavior.Instant

        // earlierClock -- BEFORE the routine's window opens, so this is Upcoming, not ActiveNow.
        val realScheduler = WorkManagerRoutineScheduler(context, earlierClock, zoneProvider)
        val realReconciler = RoutineScheduleReconciler(FakeRoutineRepository(listOf(routine)), realScheduler, RecordingWidgetUpdater())
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
            coordinatorClock = earlierClock,
            routineScheduleReconciler = realReconciler,
        )

        coordinator.onTimeZoneChanged()

        val info = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }
        // A genuine future delay, comfortably beyond a rounding/clock-skew margin -- not a
        // zero-delay ActiveNow-style enqueue, which is what a bug collapsing this into the
        // isActivationRunning-guarded path (or miscomputing the occurrence) would produce.
        assertTrue(
            "expected a real future delay, not a zero-delay reschedule",
            info.nextScheduleTimeMillis > earlierNow.toEpochMilli() + java.time.Duration.ofMinutes(1).toMillis(),
        )
    }

    @Test
    fun `a routine paused for today is scheduled for its next eligible day, not today, by onAppStart`() = runTest {
        setUpRealWorkManager()
        nextWorkerBehavior = WorkerBehavior.Instant
        // `now` (07:30, per `clock`) is INSIDE this routine's 07:00-09:00 window -- if
        // pausedDate did not survive reconciliation, this would be scheduled as ActiveNow (a
        // zero delay) instead of skipping to its next eligible day.
        val pausedRoutine = routine(id = "r1", pausedDate = LocalDate.of(2026, 7, 27)) // today, per `clock`
        val realScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = false)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(pausedRoutine)),
            scheduler = realScheduler,
            pendingStore = pendingStore,
            available = true,
        )

        coordinator.onAppStart()

        val info = workInfosFor(pausedRoutine.id).single { it.state == WorkInfo.State.ENQUEUED }
        // activeDays = {MONDAY} only (the default routine()) and today (2026-07-27) IS a Monday
        // -- if today is correctly excluded, the next eligible day is a full week later, not
        // merely later today.
        assertTrue(
            "expected pausedDate to exclude today, scheduling a future day rather than today's " +
                "already-open window",
            info.nextScheduleTimeMillis > now.toEpochMilli() + java.time.Duration.ofHours(1).toMillis(),
        )
    }

    // ---- 10 (real-WorkManager counterpart). An out-of-window future schedule is left byte-for-
    // byte untouched by a foreground recovery attempt ----

    @Test
    fun `foregrounding outside the active window preserves the exact previously scheduled future work`() = runTest {
        setUpRealWorkManager()
        val routine = routine()
        nextWorkerBehavior = WorkerBehavior.Instant

        // Scheduled well before the window opens -- Upcoming, a real multi-hour delay that never
        // elapses in this test, so it stays ENQUEUED under its own work id.
        val earlierScheduler = WorkManagerRoutineScheduler(context, earlierClock, zoneProvider)
        earlierScheduler.scheduleActivation(routine)
        val before = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }

        val pendingStore = InMemoryRecoveryPendingStateStore(initiallyPending = true)
        val coordinator = buildCoordinator(
            repository = FakeRoutineRepository(listOf(routine)),
            scheduler = earlierScheduler,
            pendingStore = pendingStore,
            available = true,
            coordinatorClock = earlierClock,
        )

        coordinator.onForeground()

        val after = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("a routine outside its active window must be left completely untouched", before.id, after.id)
        assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
        assertEquals(false, pendingStore.recoveryPending.first())
    }
}
