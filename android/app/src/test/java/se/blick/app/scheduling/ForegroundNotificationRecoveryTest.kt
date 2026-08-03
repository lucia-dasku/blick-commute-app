package se.blick.app.scheduling

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.Worker
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationAvailabilityStateStore
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the foreground-recovery fix: [BlickApplication] used to call
 * [RoutineScheduleReconciler.reconcileAll] on every single app foreground, and that call's
 * `ExistingWorkPolicy.REPLACE` could cancel and replace an already-`RUNNING`
 * [RoutineActiveWindowWorker] merely because the user opened the app (notification/widget
 * flicker, duplicate API requests, lost in-memory disruption fallback, and a race where the
 * cancelled worker's own `finally` could clear content a replacement worker had already posted).
 * [ForegroundNotificationRecovery] replaces that call — these tests exercise it against a REAL
 * (in-memory) [WorkManager] instance, backed by a genuine background thread pool executor (not
 * [androidx.work.testing.SynchronousExecutor]) so a worker can be driven into, and observed in,
 * an actual `RUNNING` state while the test thread concurrently calls [ForegroundNotificationRecovery.onForeground]
 * — exactly the scenario the bug depends on. A custom [WorkerFactory] substitutes a lightweight,
 * fully test-controlled [Worker] for [RoutineActiveWindowWorker] (same pattern as
 * `WorkManagerRoutineSchedulerTest`/`WidgetReconcileWorkerTest`'s own custom factories), so no
 * Hilt component or real notification/departures plumbing is needed — only WorkManager's own
 * real scheduling/state-transition behavior is under test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ForegroundNotificationRecoveryTest {

    // Monday 2026-07-27, 07:30 Europe/Stockholm (CEST, UTC+2) -- INSIDE the default routine()'s
    // 07:00-09:00 window.
    private val now: Instant = Instant.parse("2026-07-27T05:30:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    // Same Monday, 05:00 Stockholm -- BEFORE the window opens.
    private val earlierNow: Instant = Instant.parse("2026-07-27T03:00:00Z")
    private val earlierClock: Clock = Clock.fixed(earlierNow, ZoneOffset.UTC)

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val zoneProvider = DeviceZoneProvider { zone }

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    /** Consulted fresh every time WorkManager actually constructs a worker -- lets each test
     * set the behavior it needs immediately before its own `scheduleActivation` call. */
    private var nextWorkerBehavior: WorkerBehavior = WorkerBehavior.Instant

    private sealed interface WorkerBehavior {
        data object Instant : WorkerBehavior
        data class Blocking(
            val startedLatch: CountDownLatch,
            val releaseLatch: CountDownLatch,
            val finishedLatch: CountDownLatch = CountDownLatch(1),
            val onStart: () -> Unit = {},
        ) : WorkerBehavior
    }

    @Before
    fun setUp() {
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
                            behavior.onStart()
                            behavior.startedLatch.countDown()
                            behavior.releaseLatch.await()
                            behavior.finishedLatch.countDown()
                            return Result.success()
                        }
                    }
                }
            }
        }
        // A REAL background executor -- not SynchronousExecutor -- so a Blocking worker
        // genuinely occupies a background thread in RUNNING state while the test thread
        // proceeds to call ForegroundNotificationRecovery.onForeground() concurrently.
        val config = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setExecutor(Executors.newCachedThreadPool())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun routine(
        id: String = "r1",
        pausedDate: LocalDate? = null,
    ) = CommuteRoutine(
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

    private fun workInfosFor(routineId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerRoutineScheduler.uniqueWorkName(routineId)).get()

    /** Bounded, short real-time poll -- there is no virtual-time hook into WorkManager's own
     * background executor, so waiting for a specific terminal state after releasing a
     * [WorkerBehavior.Blocking] worker (a genuine cross-thread hand-off) has no fully
     * deterministic alternative here. Kept short and used only as a final-state check AFTER a
     * latch has already confirmed the worker itself finished running. */
    private fun awaitState(routineId: String, expected: WorkInfo.State, timeoutMs: Long = 3_000): WorkInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            workInfosFor(routineId).firstOrNull { it.state == expected }?.let { return it }
            Thread.sleep(20)
        }
        error("Timed out waiting for routine $routineId to reach $expected; last seen: ${workInfosFor(routineId)}")
    }

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

    private class FakeNotificationAvailabilityChecker(
        private val available: Boolean,
    ) : NotificationAvailabilityChecker {
        override fun check(): NotificationAvailability =
            if (available) NotificationAvailability.Available else NotificationAvailability.AppDisabled
    }

    /** In-memory stand-in for the DataStore-backed store -- this test file is about WorkManager
     * behavior, not DataStore persistence mechanics (see
     * `PreferencesNotificationAvailabilityStateStoreTest` for a direct test of that). */
    private class FakeNotificationAvailabilityStateStore(initial: Boolean?) : NotificationAvailabilityStateStore {
        private val state = MutableStateFlow(initial)
        override val lastKnownAvailable: Flow<Boolean?> = state
        override suspend fun setLastKnownAvailable(available: Boolean) {
            state.value = available
        }
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

    private fun buildRecovery(
        routines: List<CommuteRoutine>,
        previouslyAvailable: Boolean?,
        currentlyAvailable: Boolean,
        scheduler: RoutineScheduler,
        recoveryClock: Clock = clock,
        widgetUpdater: RecordingWidgetUpdater = RecordingWidgetUpdater(),
    ) = ForegroundNotificationRecovery(
        routineRepository = FakeRoutineRepository(routines),
        routineScheduler = scheduler,
        routineWidgetUpdater = widgetUpdater,
        notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(currentlyAvailable),
        stateStore = FakeNotificationAvailabilityStateStore(previouslyAvailable),
        clock = recoveryClock,
        deviceZoneProvider = zoneProvider,
    )

    // ---- 1. RUNNING work is left completely untouched ----

    @Test
    fun `foregrounding while routine work is RUNNING does not cancel, replace, or change its work id`() = runTest {
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch)

        val scheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        scheduler.scheduleActivation(routine) // ActiveNow -> zero delay -> starts on the real executor

        assertTrue("worker never started running", startedLatch.await(5, TimeUnit.SECONDS))
        val runningBefore = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }

        buildRecovery(
            routines = listOf(routine),
            previouslyAvailable = false,
            currentlyAvailable = true,
            scheduler = scheduler,
        ).onForeground()

        val afterRecovery = workInfosFor(routine.id).single { it.state == WorkInfo.State.RUNNING }
        assertEquals(runningBefore.id, afterRecovery.id)

        releaseLatch.countDown() // let the worker finish so it doesn't leak past this test
    }

    // ---- 2. No transition -> no reschedule at all ----

    @Test
    fun `repeated foreground events while notifications remain available do not reschedule work`() = runTest {
        val routine = routine()
        val scheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        assertTrue("expected nothing scheduled yet", workInfosFor(routine.id).isEmpty())

        // Available -> Available, twice -- never a transition.
        buildRecovery(listOf(routine), previouslyAvailable = true, currentlyAvailable = true, scheduler = scheduler).onForeground()
        buildRecovery(listOf(routine), previouslyAvailable = true, currentlyAvailable = true, scheduler = scheduler).onForeground()

        assertTrue("recovery must not schedule anything without a transition", workInfosFor(routine.id).isEmpty())
    }

    // ---- 3. A genuine transition during the active window starts the missing worker ----

    @Test
    fun `an unavailable-to-available transition during an active window starts the missing worker immediately`() = runTest {
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch)
        val scheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)

        assertTrue("nothing should be scheduled before recovery", workInfosFor(routine.id).isEmpty())

        buildRecovery(
            routines = listOf(routine),
            previouslyAvailable = false,
            currentlyAvailable = true,
            scheduler = scheduler,
        ).onForeground()

        assertTrue(
            "recovery should have started the missing worker for today's active window",
            startedLatch.await(5, TimeUnit.SECONDS),
        )
        releaseLatch.countDown()
    }

    // ---- 4. A stale next-day request is replaced ONLY when today's worker is absent ----

    @Test
    fun `recovery replaces a next-day request only when today's active worker is absent`() = runTest {
        val routine = routine()
        nextWorkerBehavior = WorkerBehavior.Instant

        // Scheduled earlier (before the window opened) -- computes NextOccurrence.Upcoming for
        // TODAY's own window and enqueues with a real, multi-hour delay that never elapses in
        // this test, so it stays ENQUEUED under its own work id, never RUNNING.
        val earlierScheduler = WorkManagerRoutineScheduler(context, earlierClock, zoneProvider)
        earlierScheduler.scheduleActivation(routine)
        val staleInfo = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }

        // Time has now moved into the active window; nothing is RUNNING (the stale request
        // above never fired), so recovery must replace it with an immediate activation.
        val nowScheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        buildRecovery(
            routines = listOf(routine),
            previouslyAvailable = false,
            currentlyAvailable = true,
            scheduler = nowScheduler,
        ).onForeground()

        val nonCancelled = workInfosFor(routine.id).filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals("expected exactly one live entry after the replace", 1, nonCancelled.size)
        assertNotEquals(
            "the stale next-day request's own work id must have been superseded",
            staleInfo.id,
            nonCancelled.single().id,
        )
    }

    // ---- 5. Outside the active window, the existing future schedule is preserved ----

    @Test
    fun `foregrounding outside the active window preserves the correctly scheduled future work`() = runTest {
        val routine = routine()
        nextWorkerBehavior = WorkerBehavior.Instant
        val earlierScheduler = WorkManagerRoutineScheduler(context, earlierClock, zoneProvider)
        earlierScheduler.scheduleActivation(routine) // Upcoming -- correctly scheduled, well before window start
        val before = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }

        // A genuine transition happens, but "now" is still BEFORE the window opens.
        buildRecovery(
            routines = listOf(routine),
            previouslyAvailable = false,
            currentlyAvailable = true,
            scheduler = earlierScheduler,
            recoveryClock = earlierClock,
        ).onForeground()

        val after = workInfosFor(routine.id).single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("a routine outside its active window must be left completely untouched", before.id, after.id)
        assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
    }

    // ---- 6. A RUNNING worker's content can never be clobbered by a "replacement" ----

    @Test
    fun `a cancelled old worker cannot clear notification or widget content belonging to newer work`() = runTest {
        val routine = routine()
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        val contentPostedCount = AtomicInteger(0)
        nextWorkerBehavior = WorkerBehavior.Blocking(startedLatch, releaseLatch, finishedLatch) {
            contentPostedCount.incrementAndGet()
        }
        val scheduler = WorkManagerRoutineScheduler(context, clock, zoneProvider)
        scheduler.scheduleActivation(routine)

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        assertEquals("the running worker should have posted its content exactly once", 1, contentPostedCount.get())

        // If this call cancelled the running worker and enqueued a "replacement" the way the
        // old, unconditional reconcileAll() call used to, a SECOND worker instance would start
        // and post its own content while (or racing with) the first worker's own `finally`
        // clean-up runs -- exactly the clobbering race this fix exists to prevent.
        buildRecovery(
            routines = listOf(routine),
            previouslyAvailable = false,
            currentlyAvailable = true,
            scheduler = scheduler,
        ).onForeground()

        releaseLatch.countDown()
        assertTrue("the original worker must finish naturally, not be interrupted", finishedLatch.await(5, TimeUnit.SECONDS))
        val finalInfo = awaitState(routine.id, WorkInfo.State.SUCCEEDED)

        assertNotEquals(WorkInfo.State.CANCELLED, finalInfo.state)
        assertEquals(
            "no second, competing worker instance should ever have started",
            1,
            contentPostedCount.get(),
        )
    }
}
