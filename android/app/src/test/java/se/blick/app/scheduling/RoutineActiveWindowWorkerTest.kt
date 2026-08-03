package se.blick.app.scheduling

import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.data.repository.DisruptionRepository
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.GetDisruptionsUseCase
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.RoutineNotificationBuilder
import se.blick.app.notification.RoutineNotificationContent
import se.blick.app.notification.RoutineNotificationModel
import se.blick.app.notification.RoutineNotifier
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.widget.RoutineWidgetUpdater
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Exercises [RoutineActiveWindowWorker.doWork] directly via WorkManager's own
 * [TestListenableWorkerBuilder] (real `setForeground`/progress plumbing, no real device or
 * foreground service actually started) with a custom [WorkerFactory] supplying fakes for every
 * injected dependency except [RoutineNotificationBuilder], which is exercised for real (as in
 * `RoutineNotificationBuilderTest`) since it needs a real [Context] anyway.
 *
 * A [TickingClock] test double — not [Clock.fixed] — is used throughout so the worker's own
 * repeated `ZonedDateTime.now(clock)` window-end checks can be driven forward deterministically
 * in lockstep with [FakeRoutineRepository.getById] calls (one simulated "30 seconds" per loop
 * iteration), without this test needing to wait on, or precisely fake, the coroutine
 * dispatcher's own virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoutineActiveWindowWorkerTest {

    private val zone: ZoneId = ZoneOffset.of("+02:00") // Europe/Stockholm summer time, as a fixed offset
    private val zoneProvider = DeviceZoneProvider { zone }
    private val context: Context = RuntimeEnvironment.getApplication()
    private val notificationBuilder = RoutineNotificationBuilder(context)
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** A settable [Clock] -- see class doc for why this, not [Clock.fixed], is used here. */
    private class TickingClock(startInstant: Instant, private val zoneId: ZoneId) : Clock() {
        var instant: Instant = startInstant
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zoneId: ZoneId): Clock = TickingClock(instant, zoneId)
        override fun instant(): Instant = instant
    }

    private fun routine(
        id: String = "r1",
        enabled: Boolean = true,
        startTime: LocalTime = LocalTime.of(7, 0),
        endTime: LocalTime = LocalTime.of(7, 2),
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
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
    )

    private fun sampleDeparture() = Departure(
        departureId = UUID.randomUUID().toString(),
        line = LineRef(id = 14, designation = "14", transportMode = TransportMode.METRO),
        direction = "T-Centralen",
        directionCode = 1,
        destination = "T-Centralen",
        via = null,
        stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "METROSTN"),
        stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A"),
        scheduledTime = Instant.parse("2026-07-27T05:05:00Z"),
        expectedTime = null,
        state = "EXPECTED",
        isCancelled = false,
        journey = Journey(id = 1, state = "EXPECTED", predictionState = null),
        tripDeviations = emptyList(),
    )

    /** Advances [clock] by [advanceSecondsPerCall] on every [getById] call (simulating "time
     * passed since the last loop iteration") and returns whatever [routineForCall] produces for
     * that call index -- lets a test script exactly when a routine becomes disabled, is
     * deleted, or a window boundary is crossed, independent of the coroutine dispatcher's own
     * virtual time. */
    private class ScriptedRoutineRepository(
        private val clock: TickingClock,
        private val advanceSecondsPerCall: Long = 30,
        private val routineForCall: (callIndex: Int) -> CommuteRoutine?,
    ) : RoutineRepository {
        var callCount = 0
        override fun observeAll() = throw NotImplementedError("unused by RoutineActiveWindowWorker")
        override suspend fun getById(id: String): CommuteRoutine? {
            val result = routineForCall(callCount)
            callCount++
            clock.instant = clock.instant.plusSeconds(advanceSecondsPerCall)
            return result
        }
        override suspend fun save(routine: CommuteRoutine) = throw NotImplementedError()
        override suspend fun delete(id: String) = throw NotImplementedError()
        override suspend fun pauseForDate(id: String, date: java.time.LocalDate) = throw NotImplementedError()
        override suspend fun clearPause(id: String) = throw NotImplementedError()
        override suspend fun setEnabled(id: String, enabled: Boolean) = throw NotImplementedError()
        override suspend fun hasAnyRoutine(): Boolean = throw NotImplementedError()
    }

    private class FakeDepartureRepository(private val result: () -> DeparturesResult) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            callCount++
            return result()
        }
    }

    private fun sampleDisruption(id: String = "d1", importance: Int = 1) = Disruption(
        disruptionId = id,
        version = 1,
        createdAt = Instant.parse("2026-07-27T05:00:00Z"),
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(importance, 1, 1),
        message = DisruptionMessage("Header $id", "Details $id", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    /** Defaults to an empty result -- most tests below don't care about disruptions at all. */
    private class FakeDisruptionRepository(
        private val result: () -> List<Disruption> = { emptyList() },
    ) : DisruptionRepository {
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            callCount++
            return result()
        }
    }

    private class FailingDisruptionRepository(private val error: Throwable) : DisruptionRepository {
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            callCount++
            throw error
        }
    }

    /** Never actually resolves within [delayMs] -- used to prove [DISRUPTIONS_FETCH_TIMEOUT_MS]
     * genuinely bounds how long the worker waits on this, rather than departures merely
     * happening to arrive first by chance. */
    private class SlowDisruptionRepository(
        private val delayMs: Long,
        private val result: () -> List<Disruption> = { emptyList() },
    ) : DisruptionRepository {
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            callCount++
            delay(delayMs)
            return result()
        }
    }

    /** Returns whatever [resultForCall] produces for the current call index, EXCEPT a `null`
     * result means "never resolves in time" (simulated via a delay far longer than
     * [DISRUPTIONS_FETCH_TIMEOUT_MS], like [SlowDisruptionRepository]) -- lets a test script a
     * successful fetch on one tick and a timed-out one on the next. */
    private class ScriptedDisruptionRepository(
        private val resultForCall: (callIndex: Int) -> List<Disruption>?,
    ) : DisruptionRepository {
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            val result = resultForCall(callCount)
            callCount++
            if (result == null) delay(DISRUPTIONS_FETCH_TIMEOUT_MS * 100)
            return result ?: emptyList()
        }
    }

    /** Advances the shared [clock] by [advanceMs] (simulating real wall-clock time spent
     * awaiting this fetch, exactly like [ScriptedRoutineRepository]'s own per-call advance)
     * before returning [result] -- used to prove elapsed disruption-fetch time is subtracted
     * from the tick's subsequent delay rather than added on top of it. */
    private class SlowClockAdvancingDisruptionRepository(
        private val clock: TickingClock,
        private val delayMs: Long,
        private val result: () -> List<Disruption> = { emptyList() },
    ) : DisruptionRepository {
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            delay(delayMs)
            clock.instant = clock.instant.plusMillis(delayMs)
            return result()
        }
    }

    /** Records every posted model and every remove() call; can also be scripted to throw on a
     * specific call index, simulating an unexpected failure mid-loop (distinct from the normal
     * NotificationsDisabled/Failed return-value path, which never throws — see
     * [AndroidRoutineNotifier]) to prove the worker still cleans up and reschedules. */
    private class RecordingNotifier(
        private val result: NotificationPostResult = NotificationPostResult.Posted,
        private val throwOnShowCall: Int? = null,
    ) : RoutineNotifier {
        val shown = mutableListOf<RoutineNotificationModel>()
        var removeCallCount = 0
        private var showCallCount = 0
        override fun showOrUpdate(model: RoutineNotificationModel): NotificationPostResult {
            showCallCount++
            if (throwOnShowCall == showCallCount) throw RuntimeException("unexpected notifier failure")
            shown += model
            return result
        }
        override fun remove() {
            removeCallCount++
        }
    }

    /** Records every widget-update/clear/reconcile/showNotificationsUnavailable call — for
     * proving each loop tick updates the widget with the exact same
     * [CommuteRoutine]/[LiveDeparturesState] already fetched for the notification (no separate
     * fetch), and that every exit path that cannot continue an active commute touches the widget
     * exactly once, with the right method. */
    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        val updateCalls = mutableListOf<Pair<CommuteRoutine, LiveDeparturesState>>()
        val notificationsUnavailableCalls = mutableListOf<CommuteRoutine>()
        var clearCallCount = 0
        var reconcileCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            updateCalls += routine to departuresState
        }
        override suspend fun clear() {
            clearCallCount++
        }
        override suspend fun reconcile() {
            reconcileCallCount++
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) {
            notificationsUnavailableCalls += routine
        }
    }

    /** Throws from every method — proves every `routineWidgetUpdater.*` call inside [doWork]
     * wraps with `runWidgetUpdateSafely` rather than letting a widget/Glance/DataStore failure
     * escalate into this function's own `catch (e: Exception)` (which would incorrectly treat
     * it as a "handled failure" and cut the whole active-window loop short) or crash out of a
     * `finally` block. */
    private class FailingWidgetUpdater : RoutineWidgetUpdater {
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant): Unit =
            throw RuntimeException("widget update failed")
        override suspend fun clear(): Unit = throw RuntimeException("widget update failed")
        override suspend fun reconcile(): Unit = throw RuntimeException("widget update failed")
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine): Unit =
            throw RuntimeException("widget update failed")
    }

    private class RecordingScheduler : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) = Unit
    }

    /** Proves the worker never asks [RoutineScheduler] to reschedule an occurrence that is
     * STILL [NextOccurrence.ActiveNow] right now -- exactly reproducing
     * [WorkManagerRoutineScheduler]'s own `excludedDate = routine.pausedDate` calculation against
     * the single [CommuteRoutine] that was actually scheduled, using the real occurrence
     * calculator rather than asserting on [RoutineActiveWindowWorker]'s private implementation
     * detail. If this failed, [WorkManagerRoutineScheduler] would enqueue that occurrence with a
     * ZERO initial delay, and the worker would immediately re-run and hit the same condition
     * again -- a tight zero-delay rescheduling loop. */
    private fun assertNoZeroDelayReschedule(scheduler: RecordingScheduler, clock: TickingClock) {
        assertEquals(1, scheduler.scheduledRoutines.size)
        val scheduled = scheduler.scheduledRoutines.single()
        val now = ZonedDateTime.ofInstant(clock.instant, zone)
        val occurrence = NextOccurrenceCalculator.nextOccurrence(scheduled, now, excludedDate = scheduled.pausedDate)
        assertTrue(
            "expected the rescheduled occurrence to skip today's still-open window, but got $occurrence",
            occurrence !is NextOccurrence.ActiveNow,
        )
    }

    /** Settable fake — see [NotificationAvailabilityChecker]'s own doc for why this is the one
     * shared seam the worker (and [se.blick.app.notification.AndroidRoutineNotifier]/the
     * details screen) all read through. Defaults to [NotificationAvailability.Available] so
     * existing tests that don't care about this concern are unaffected. */
    private class FakeNotificationAvailabilityChecker(
        var current: NotificationAvailability = NotificationAvailability.Available,
    ) : NotificationAvailabilityChecker {
        override fun check(): NotificationAvailability = current
    }

    /** Scripted variant of [FakeNotificationAvailabilityChecker] whose answer depends on the
     * call index -- lets a test drive notifications from [NotificationAvailability.Available]
     * to unavailable mid-loop, independent of the routine repository's own call count. */
    private class ScriptedNotificationAvailabilityChecker(
        private val availabilityForCall: (callIndex: Int) -> NotificationAvailability,
    ) : NotificationAvailabilityChecker {
        var callCount = 0
        override fun check(): NotificationAvailability {
            val result = availabilityForCall(callCount)
            callCount++
            return result
        }
    }

    /** In-memory [StaleSnapshotRepository] fake — see the identical fake's doc in
     * `RoutineDetailsViewModelTest` for why a SHARED instance passed to two separately-built
     * workers is exactly how a test simulates "the process was killed and restarted between
     * these two runs": neither worker instance carries any in-memory field of its own, so
     * whatever the first one persisted is all the second one can see. */
    private class FakeStaleSnapshotRepository : StaleSnapshotRepository {
        private val stored = mutableMapOf<String, Pair<DepartureIdentity, LiveDeparturesSnapshot>>()
        override suspend fun get(routineId: String, identity: DepartureIdentity): LiveDeparturesSnapshot? {
            val (storedIdentity, snapshot) = stored[routineId] ?: return null
            return snapshot.takeIf { storedIdentity == identity }
        }
        override suspend fun save(routineId: String, identity: DepartureIdentity, snapshot: LiveDeparturesSnapshot) {
            stored[routineId] = identity to snapshot
        }
        override suspend fun clear(routineId: String) {
            stored.remove(routineId)
        }
    }

    private fun buildWorker(
        routineId: String,
        routineRepository: RoutineRepository,
        getLiveDepartures: GetLiveDeparturesUseCase,
        notifier: RoutineNotifier,
        scheduler: RoutineScheduler,
        clock: Clock,
        notificationAvailabilityChecker: NotificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
        notificationBuilder: RoutineNotificationBuilder = this.notificationBuilder,
        deviceZoneProvider: DeviceZoneProvider = zoneProvider,
        staleSnapshotRepository: StaleSnapshotRepository = FakeStaleSnapshotRepository(),
        widgetUpdater: RoutineWidgetUpdater = RecordingWidgetUpdater(),
        getDisruptions: GetDisruptionsUseCase = GetDisruptionsUseCase(FakeDisruptionRepository()),
    ): RoutineActiveWindowWorker =
        TestListenableWorkerBuilder<RoutineActiveWindowWorker>(context)
            .setInputData(workDataOf(RoutineActiveWindowWorker.KEY_ROUTINE_ID to routineId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = RoutineActiveWindowWorker(
                    appContext,
                    workerParameters,
                    routineRepository,
                    getLiveDepartures,
                    getDisruptions,
                    staleSnapshotRepository,
                    notifier,
                    notificationBuilder,
                    widgetUpdater,
                    scheduler,
                    notificationAvailabilityChecker,
                    clock,
                    deviceZoneProvider,
                )
            })
            .build()

    @Test
    fun `returns failure and reconciles the widget when the routine id is missing from input data`() = runTest {
        val widgetUpdater = RecordingWidgetUpdater()
        val worker = TestListenableWorkerBuilder<RoutineActiveWindowWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    RoutineActiveWindowWorker(
                        appContext,
                        workerParameters,
                        ScriptedRoutineRepository(TickingClock(Instant.now(), zone)) { null },
                        GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, Clock.systemUTC()),
                        GetDisruptionsUseCase(FakeDisruptionRepository()),
                        FakeStaleSnapshotRepository(),
                        RecordingNotifier(),
                        notificationBuilder,
                        widgetUpdater,
                        RecordingScheduler(),
                        FakeNotificationAvailabilityChecker(),
                        Clock.systemUTC(),
                        zoneProvider,
                    )
            })
            .build()

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `does nothing, succeeds, and reconciles the widget when the routine no longer exists`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val repository = ScriptedRoutineRepository(clock) { null }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            "r1",
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // Deleted (or never existed) -- the widget must not keep showing a routine that no
        // longer exists, so reconcile() (not clear()) recomputes the correct state from scratch.
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `a disabled routine is skipped, rescheduled, and reconciles the widget`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val disabledRoutine = routine(enabled = false)
        val repository = ScriptedRoutineRepository(clock) { disabledRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            disabledRoutine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // A disabled routine is never rescheduled by this run (RoutineScheduler's own callers
        // handle re-enabling); the widget must still be reconciled so it doesn't keep showing a
        // now-disabled routine as active.
        assertTrue(scheduler.scheduledRoutines.isEmpty())
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `a routine outside its window (late start) is skipped, rescheduled without posting, and reconciles the widget`() = runTest {
        // Window is 07:00-07:02, but the ticking clock starts at 08:00 -- WorkManager ran this
        // far later than intended (or it was somehow enqueued too early); either way this
        // occurrence is stale.
        val clock = TickingClock(Instant.parse("2026-07-27T06:00:00Z"), zone) // 08:00 local
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `fetches and posts on each tick until the window ends, then removes the notification and reschedules`() = runTest {
        // Window 07:00-07:02 (2 minutes); ScriptedRoutineRepository advances the clock 30s per
        // call, so the loop naturally crosses the window boundary after a few iterations.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Exactly two ticks land inside the 2-minute window before the boundary is crossed
        // (see the class doc's worked example) -- never zero, and never "however many
        // iterations happen to run."
        assertEquals(2, notifier.shown.size)
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    // ---- Widget updates mirror the notification loop exactly (same tick, same already-fetched
    // departuresState -- no separate fetch, no separate timer; see RoutineWidgetUpdater's doc) ----

    @Test
    fun `each tick updates the widget with the same routine and departures state as the notification`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
        )
        worker.doWork()

        // Same tick count as the notification (see the test above), and each one carries a
        // real Live state -- proving the exact fetched departuresState was reused, not a
        // second independent fetch.
        assertEquals(2, widgetUpdater.updateCalls.size)
        assertTrue(widgetUpdater.updateCalls.all { (r, state) -> r.id == routine.id && state is LiveDeparturesState.Live })
    }

    @Test
    fun `the widget is cleared exactly when the notification is removed, at window end`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
        )
        worker.doWork()

        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, widgetUpdater.clearCallCount)
    }

    @Test
    fun `notifications unavailable at startup -- the widget is never updated, never cleared, but shown honestly, and today is skipped when rescheduling`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.AppDisabled)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
        )
        worker.doWork()

        assertTrue(widgetUpdater.updateCalls.isEmpty())
        // enteredForeground never became true (see notifier.removeCallCount's own assertion
        // elsewhere), so the widget-clear in the same `finally` never ran either.
        assertEquals(0, widgetUpdater.clearCallCount)
        // The window IS genuinely active here -- the widget must say so honestly instead of
        // being left on Loading forever, or silently reverting to "No active commute."
        assertEquals(listOf(routine.id), widgetUpdater.notificationsUnavailableCalls.map { it.id })
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    // ---- Regression: a STALE pausedDate must never suppress today's own skip-today reschedule
    // (Fix: rescheduleSkippingToday always overwrites pausedDate with today, never `?: today`) ----

    @Test
    fun `notifications unavailable at startup, with a stale pausedDate from yesterday -- today is still skipped, no zero-delay loop`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        // A routine that was paused for a PREVIOUS day and never touched since -- pausedDate
        // simply still holds yesterday's date. Reproduces the exact bug: the old
        // `latest.pausedDate ?: today` left this stale date in place instead of today's, so
        // NextOccurrenceCalculator no longer excluded today's still-open window at all.
        val routine = routine().copy(pausedDate = LocalDate.of(2026, 7, 26))
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.AppDisabled)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
        )
        worker.doWork()

        assertEquals(listOf(routine.id), widgetUpdater.notificationsUnavailableCalls.map { it.id })
        // The core regression assertion: with the bug, the rescheduled routine's stale
        // pausedDate (2026-07-26) would leave today (2026-07-27) unexcluded, and
        // NextOccurrenceCalculator would report it as still ActiveNow -- exactly what
        // assertNoZeroDelayReschedule exists to catch.
        assertNoZeroDelayReschedule(scheduler, clock)
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `a handled failure with a stale pausedDate from yesterday -- today is still skipped, no zero-delay loop`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine().copy(pausedDate = LocalDate.of(2026, 7, 26))
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val failingBuilder = mockk<RoutineNotificationBuilder>()
        every { failingBuilder.build(any()) } throws RuntimeException("boom during foreground setup")

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationBuilder = failingBuilder,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNoZeroDelayReschedule(scheduler, clock)
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `every posted notification model carries the routine's own id for correct tap navigation`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine(id = "r-42")
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
        )
        worker.doWork()

        assertTrue(notifier.shown.isNotEmpty())
        assertTrue(notifier.shown.all { it.routineId == "r-42" })
    }

    @Test
    fun `the routine being disabled mid-run stops the loop early and removes the notification`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val enabledRoutine = routine()
        // Disabled from the third getById call onward (call index 2) -- the first is the
        // initial load, the second is the first loop iteration's re-check.
        val repository = ScriptedRoutineRepository(clock) { callIndex ->
            if (callIndex < 2) enabledRoutine else enabledRoutine.copy(enabled = false)
        }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            enabledRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
        )
        worker.doWork()

        assertEquals(1, notifier.shown.size) // only the one tick before being disabled
        assertEquals(1, notifier.removeCallCount)
        assertEquals(listOf(false), scheduler.scheduledRoutines.map { it.enabled })
    }

    @Test
    fun `notifications becoming unavailable mid-run stops the loop early, reschedules, and shows the widget honestly instead of clearing it`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val enabledRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { enabledRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        // Call index 0 is the pre-loop check in doWork(), call index 1 is the first tick's
        // re-check (still available) -- then AppDisabled from the second tick's re-check
        // onward, simulating the user turning notifications off partway through the window.
        val checker = ScriptedNotificationAvailabilityChecker { callIndex ->
            if (callIndex < 2) NotificationAvailability.Available else NotificationAvailability.AppDisabled
        }

        val worker = buildWorker(
            enabledRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
        )
        worker.doWork()

        assertEquals(1, notifier.shown.size) // only the one tick before notifications became unavailable
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        // The window is still genuinely open -- the `finally` must show the honest
        // "notifications unavailable" state here, NOT clear() (which would falsely claim there's
        // no active commute at all).
        assertEquals(0, widgetUpdater.clearCallCount)
        assertEquals(listOf(enabledRoutine.id), widgetUpdater.notificationsUnavailableCalls.map { it.id })
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    @Test
    fun `the routine being deleted mid-run stops the loop and does not reschedule`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val existingRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) existingRoutine else null }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            existingRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
        )
        worker.doWork()

        assertEquals(1, notifier.removeCallCount)
        assertTrue(scheduler.scheduledRoutines.isEmpty())
    }

    @Test
    fun `a fetch failure after a successful tick preserves stale data without an extra alert`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        var callCount = 0
        val departures = object : DepartureRepository {
            override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
                callCount++
                if (callCount == 1) return DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()))
                throw IOException("network down")
            }
        }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
        )
        worker.doWork()

        assertEquals(2, notifier.shown.size)
        assertTrue(notifier.shown[0].content is RoutineNotificationContent.Live)
        // The second tick's fetch failed, but a previous successful snapshot exists -- the
        // worker must carry it forward as Stale, exactly like RoutineDetailsViewModel's own
        // stale-fallback behaviour, rather than reporting Offline/Unavailable and losing it.
        assertTrue(notifier.shown[1].content is RoutineNotificationContent.Stale)
        assertEquals(1, notifier.removeCallCount)
    }

    @Test
    fun `stale data persists across separate worker instances, simulating a process restart`() = runTest {
        val routine = routine()
        val staleSnapshots = FakeStaleSnapshotRepository()

        // First "run" -- succeeds, persisting a snapshot via the SHARED staleSnapshots repository.
        val firstClock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val firstRepository = ScriptedRoutineRepository(firstClock) { routine }
        val firstNotifier = RecordingNotifier()
        val firstDepartures = FakeDepartureRepository {
            DeparturesResult(firstClock.instant(), 9145, listOf(sampleDeparture()))
        }
        val firstWorker = buildWorker(
            routine.id,
            firstRepository,
            GetLiveDeparturesUseCase(firstDepartures, firstClock),
            firstNotifier,
            RecordingScheduler(),
            firstClock,
            staleSnapshotRepository = staleSnapshots,
        )
        firstWorker.doWork()
        assertTrue(firstNotifier.shown.isNotEmpty())
        assertTrue(firstNotifier.shown.all { it.content is RoutineNotificationContent.Live })

        // A second, entirely separate worker instance -- no in-memory field carried over from
        // the first -- whose OWN fetch always fails. It must still fall back to Stale using
        // the snapshot the FIRST instance persisted, proving durability survives a worker (and,
        // in production, a whole process) being torn down and recreated.
        val secondClock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val secondRepository = ScriptedRoutineRepository(secondClock) { routine }
        val secondNotifier = RecordingNotifier()
        val secondDepartures = object : DepartureRepository {
            override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult = throw IOException("network down")
        }
        val secondWorker = buildWorker(
            routine.id,
            secondRepository,
            GetLiveDeparturesUseCase(secondDepartures, secondClock),
            secondNotifier,
            RecordingScheduler(),
            secondClock,
            staleSnapshotRepository = staleSnapshots,
        )
        secondWorker.doWork()

        assertTrue(secondNotifier.shown.isNotEmpty())
        assertTrue(secondNotifier.shown[0].content is RoutineNotificationContent.Stale)
    }

    // ---- Notification availability checked before foreground execution (Fix 2) ----

    @Test
    fun `runtime permission missing -- never enters the loop, never removes, still reschedules, shows the widget honestly`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.PermissionMissing)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // enteredForeground never became true -- setForeground/the loop was never entered --
        // so remove() (only called in the `finally` guarded by that flag) never ran either.
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertEquals(listOf(routine.id), widgetUpdater.notificationsUnavailableCalls.map { it.id })
    }

    @Test
    fun `app notifications disabled -- never enters the loop, still reschedules`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.AppDisabled)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    @Test
    fun `Blick channel disabled -- never enters the loop, channel is not touched, still reschedules`() = runTest {
        // A real, pre-existing disabled channel via the real framework NotificationManager --
        // not a hand-rolled duplicate of the check.
        manager.createNotificationChannel(
            android.app.NotificationChannel(se.blick.app.notification.RoutineNotificationIds.CHANNEL_ID, "Commute departures", NotificationManager.IMPORTANCE_NONE),
        )
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.ChannelDisabled)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
        )
        worker.doWork()

        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        val channelAfter = manager.getNotificationChannel(se.blick.app.notification.RoutineNotificationIds.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channelAfter.importance)
    }

    @Test
    fun `no channel yet but otherwise available -- runs normally and the channel gets created`() = runTest {
        assertNull(manager.getNotificationChannel(se.blick.app.notification.RoutineNotificationIds.CHANNEL_ID))
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(NotificationAvailability.Available),
        )
        worker.doWork()

        assertEquals(2, notifier.shown.size)
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    // ---- Handled terminal failures still clean up and reschedule (Fix 2) ----

    @Test
    fun `a failure building the foreground notification is handled -- no loop, no crash, still reschedules, reconciles the widget`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val failingBuilder = mockk<RoutineNotificationBuilder>()
        every { failingBuilder.build(any()) } throws RuntimeException("boom during foreground setup")

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationBuilder = failingBuilder,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // The failure happened before setForeground was ever reached -- enteredForeground
        // stayed false, so remove() (finally-guarded by that flag) never ran.
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        // enteredForeground being false takes the finally's `else` branch: this run never
        // posted anything for the widget to clear, so it reconciles instead, so the widget
        // doesn't keep showing stale content from before this run started.
        assertEquals(1, widgetUpdater.reconcileCallCount)
        assertEquals(0, widgetUpdater.clearCallCount)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    @Test
    fun `an unexpected failure updating the notification mid-loop is handled -- cleans up, reschedules, and skips today (no zero-delay loop)`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        // Throws on the SECOND showOrUpdate call (the first tick posts normally, simulating a
        // real mid-loop failure rather than one on the very first attempt).
        val notifier = RecordingNotifier(throwOnShowCall = 2)
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, notifier.shown.size) // only the first, successful tick
        // setForeground DID succeed this time -- enteredForeground is true, so the `finally`
        // still removes the notification despite the mid-loop failure.
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    // ---- Real cancellation never resurrects obsolete work (Fix 2) ----

    @Test
    fun `a real coroutine cancellation propagates without rescheduling, but still cleans up the notification`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        // advanceSecondsPerCall = 0 -- the window boundary must never be crossed on its own;
        // only the explicit job.cancel() below should end this run.
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
        )

        val job = launch { worker.doWork() }
        runCurrent() // let the first tick run up to its delay(30s) suspension point
        job.cancel()
        job.join()

        // Must NOT reschedule -- whatever cancelled this (an edit/disable/delete replacing
        // this routine's unique work elsewhere) may already be enqueuing or intentionally not
        // enqueuing its own replacement; this worker resurrecting another one would leave
        // obsolete work behind.
        assertTrue(scheduler.scheduledRoutines.isEmpty())
        // Cleanup still happens even on a real cancellation.
        assertEquals(1, notifier.removeCallCount)
    }

    // ---- Widget failures are best-effort: never cut the loop short, never crash a `finally`,
    // never block rescheduling (see se.blick.app.widget.runWidgetUpdateSafely's own doc) ----

    @Test
    fun `the loop runs to a normal window-end completion, posting every tick, when the widget updater throws on every call`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = FailingWidgetUpdater(),
        )
        val result = worker.doWork()

        // If updateWithDepartures()'s failure inside the loop had leaked into doWork's own
        // `catch (e: Exception)`, this would have been treated as a "handled failure" and cut
        // the loop short after one tick instead of two, exactly like a real, unrelated crash
        // would -- even though the notification itself posted successfully both times. If the
        // finally block's own widget call had thrown instead of being wrapped, it would have
        // masked whatever this function was already returning/propagating.
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(2, notifier.shown.size)
        assertEquals(1, notifier.removeCallCount)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    @Test
    fun `an early exit (routine disabled) still reschedules nothing and returns success when the widget updater throws`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine(enabled = false)
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = FailingWidgetUpdater(),
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `notifications unavailable at startup still reschedules skipping today when the widget updater throws`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.AppDisabled)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = FailingWidgetUpdater(),
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    // ---- Disruptions fetched AFTER departures, never stopping/delaying/replacing them ----

    @Test
    fun `disruptions are fetched every tick, and a newly-discovered one triggers exactly one extra silent update`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val disruptions = FakeDisruptionRepository { listOf(sampleDisruption()) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
        )
        worker.doWork()

        // Same tick count as departures (two ticks -- see the "fetches and posts on each tick"
        // test above), fetched once per tick regardless of how many times the notification
        // itself is posted that tick.
        assertEquals(2, disruptions.callCount)
        // Three posts, not two: tick 1 posts departures alone first (nothing known about
        // disruptions yet), THEN the same disruption that every tick returns is fetched and,
        // since it's new information, triggers one extra silent "update the notification
        // afterward" post carrying it. Tick 2 already knows about it from tick 1 (lastKnownDisruption
        // persists across ticks), so its own single post already includes it and the fetch at
        // the end of tick 2 finds nothing changed -- no second post that tick.
        assertEquals(3, notifier.shown.size)
        assertEquals(null, notifier.shown[0].disruptionHeadline)
        assertEquals(sampleDisruption().message.header, notifier.shown[1].disruptionHeadline)
        assertEquals(sampleDisruption().message.header, notifier.shown[2].disruptionHeadline)
    }

    @Test
    fun `the notification eventually carries the header and details of the repository's first (highest-priority) disruption`() = runTest {
        // The repository (RemoteDisruptionRepository, via relevantDisruptions -- see
        // DisruptionTest/RemoteDisruptionRepositoryTest for that ordering's own coverage) is
        // what guarantees the list arrives already priority-ordered; the worker's own job is
        // simply to take its first entry, which this fake reproduces by returning `high` first.
        // "Eventually" -- not "every post" -- because departures are always posted first, before
        // anything about disruptions is known for that tick; see the previous test for exactly
        // when the extra, disruption-carrying post happens.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val low = sampleDisruption(id = "low", importance = 1)
        val high = sampleDisruption(id = "high", importance = 5)
        val disruptions = FakeDisruptionRepository { listOf(high, low) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
        )
        worker.doWork()

        assertTrue(notifier.shown.isNotEmpty())
        val last = notifier.shown.last()
        assertEquals(high.message.header, last.disruptionHeadline)
        assertEquals(high.message.details, last.disruptionDetails)
    }

    @Test
    fun `no disruptions produces a null disruptionHeadline, not a failure`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(FakeDisruptionRepository { emptyList() }),
        )
        worker.doWork()

        assertTrue(notifier.shown.isNotEmpty())
        assertTrue(notifier.shown.all { it.disruptionHeadline == null })
    }

    @Test
    fun `a disruptions fetch failure does not stop the loop, remove the notification, or affect departures`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val failingDisruptions = FailingDisruptionRepository(RuntimeException("disruptions upstream down"))

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            getDisruptions = GetDisruptionsUseCase(failingDisruptions),
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Same behaviour as the disruptions-free happy path: two ticks posted, one removal at
        // window end, one reschedule -- a disruptions failure is fully isolated (see
        // GetDisruptionsUseCase's own doc: it never throws besides a real cancellation, so
        // this failure surfaces only as DisruptionsState.Unavailable / a null disruptionHeadline
        // below, never as a broken loop).
        assertEquals(2, notifier.shown.size)
        assertTrue(notifier.shown.all { it.content is RoutineNotificationContent.Live })
        assertTrue(notifier.shown.all { it.disruptionHeadline == null })
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertTrue(failingDisruptions.callCount > 0)
    }

    @Test
    fun `a disruptions fetch far slower than DISRUPTIONS_FETCH_TIMEOUT_MS cannot delay the departures notification`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        // Never actually resolves in time -- 100x DISRUPTIONS_FETCH_TIMEOUT_MS, and far longer
        // than ACTIVE_WINDOW_REFRESH_INTERVAL_MS itself, so this can only pass because
        // withTimeoutOrNull genuinely bounds the wait, not because the fetch happened to finish
        // fast enough on its own.
        val slowDisruptions = SlowDisruptionRepository(delayMs = DISRUPTIONS_FETCH_TIMEOUT_MS * 100)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            getDisruptions = GetDisruptionsUseCase(slowDisruptions),
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Departures still post on every tick, exactly as in the disruptions-free happy path --
        // a slow disruptions fetch never adds an extra post (nothing ever resolves in time to
        // become "new information" worth an update) and, crucially, never delays the departures
        // post itself.
        assertEquals(2, notifier.shown.size)
        assertTrue(notifier.shown.all { it.content is RoutineNotificationContent.Live })
        assertTrue(notifier.shown.all { it.disruptionHeadline == null })
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertTrue(slowDisruptions.callCount > 0)
    }

    // ---- Regression: an expired fallback disruption must not survive a later timed-out fetch
    // (Fix: lastKnownDisruption's own validUntil is re-checked against `now` every tick) ----

    @Test
    fun `an expired fallback disruption is cleared, not shown, after a later fetch times out`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine() // 07:00-07:02 window -- exactly two ticks (see class doc example)
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        // Tick 1's own `now` lands at 05:01:00Z (see class doc's worked example); this disruption
        // is still valid then, but expires at 05:01:15Z -- BEFORE tick 2's own `now` (05:01:30Z).
        val expiringDisruption = sampleDisruption().copy(validUntil = Instant.parse("2026-07-27T05:01:15Z"))
        val disruptions = ScriptedDisruptionRepository { callIndex ->
            if (callIndex == 0) listOf(expiringDisruption) else null // tick 2's fetch times out
        }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
        )
        worker.doWork()

        // Tick 1: posts departures alone first (nothing known yet), then the newly-loaded
        // disruption arrives and triggers one extra silent post carrying it -- same pattern as
        // the "newly-discovered" test above. Tick 2: its OWN fetch times out, but the fallback it
        // would otherwise have reused already expired by tick 2's `now` -- so this tick's post
        // must show no disruption at all, not the stale one, even though nothing "new" ever
        // explicitly replaced it.
        assertEquals(3, notifier.shown.size)
        assertEquals(null, notifier.shown[0].disruptionHeadline)
        assertEquals(expiringDisruption.message.header, notifier.shown[1].disruptionHeadline)
        assertEquals(null, notifier.shown[2].disruptionHeadline)
    }

    // ---- Regression: elapsed disruption-fetch time is subtracted from the tick's own delay,
    // not added on top of it (Fix: tick spacing no longer drifts to ~35s on a slow fetch) ----

    @Test
    fun `time spent on a slow-but-successful disruptions fetch is subtracted from the tick's delay, not added on top`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine() // 07:00-07:02 window -- exactly two ticks (see class doc example)
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        // Well under DISRUPTIONS_FETCH_TIMEOUT_MS (5s), so this always resolves rather than
        // timing out -- but still genuinely slow enough to matter if its elapsed time were
        // simply added on top of ACTIVE_WINDOW_REFRESH_INTERVAL_MS instead of subtracted from it.
        val disruptions = SlowClockAdvancingDisruptionRepository(clock, delayMs = 3_000L)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
        )
        worker.doWork()

        // Without the fix, each tick's delay would still be the full ACTIVE_WINDOW_REFRESH_INTERVAL_MS
        // on top of the 3s already spent fetching, drifting total tick spacing to 2 * 33s = 66s.
        // With the fix, each tick's delay is shortened to 27s, so the two ticks' combined virtual
        // time (3s fetch + 27s delay, twice) lands exactly on 2 * ACTIVE_WINDOW_REFRESH_INTERVAL_MS.
        assertEquals(2, notifier.shown.size)
        assertEquals(2 * ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }
}
