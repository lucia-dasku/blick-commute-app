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
import kotlinx.coroutines.launch
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
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.RoutineNotificationBuilder
import se.blick.app.notification.RoutineNotificationContent
import se.blick.app.notification.RoutineNotificationModel
import se.blick.app.notification.RoutineNotifier
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
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

    private class RecordingScheduler : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) = Unit
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
                    staleSnapshotRepository,
                    notifier,
                    notificationBuilder,
                    scheduler,
                    notificationAvailabilityChecker,
                    clock,
                    deviceZoneProvider,
                )
            })
            .build()

    @Test
    fun `returns failure when the routine id is missing from input data`() = runTest {
        val worker = TestListenableWorkerBuilder<RoutineActiveWindowWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    RoutineActiveWindowWorker(
                        appContext,
                        workerParameters,
                        ScriptedRoutineRepository(TickingClock(Instant.now(), zone)) { null },
                        GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, Clock.systemUTC()),
                        FakeStaleSnapshotRepository(),
                        RecordingNotifier(),
                        notificationBuilder,
                        RecordingScheduler(),
                        FakeNotificationAvailabilityChecker(),
                        Clock.systemUTC(),
                        zoneProvider,
                    )
            })
            .build()

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `does nothing and succeeds when the routine no longer exists`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val repository = ScriptedRoutineRepository(clock) { null }
        val notifier = RecordingNotifier()

        val worker = buildWorker(
            "r1",
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            RecordingScheduler(),
            clock,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `a routine outside its window (late start) is skipped and rescheduled without posting`() = runTest {
        // Window is 07:00-07:02, but the ticking clock starts at 08:00 -- WorkManager ran this
        // far later than intended (or it was somehow enqueued too early); either way this
        // occurrence is stale.
        val clock = TickingClock(Instant.parse("2026-07-27T06:00:00Z"), zone) // 08:00 local
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(1, scheduler.scheduledRoutines.size)
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
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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

    @Test
    fun `every posted notification model carries the routine's own id for correct tap navigation`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine(id = "r-42")
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
    fun `notifications becoming unavailable mid-run stops the loop early and reschedules`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val enabledRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { enabledRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }
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
        )
        worker.doWork()

        assertEquals(1, notifier.shown.size) // only the one tick before notifications became unavailable
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    @Test
    fun `the routine being deleted mid-run stops the loop and does not reschedule`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val existingRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) existingRoutine else null }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
                if (callCount == 1) return DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList())
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
            DeparturesResult(firstClock.instant(), 9145, listOf(sampleDeparture()), emptyList())
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
    fun `runtime permission missing -- never enters the loop, never removes, still reschedules`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val checker = FakeNotificationAvailabilityChecker(NotificationAvailability.PermissionMissing)

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
        // enteredForeground never became true -- setForeground/the loop was never entered --
        // so remove() (only called in the `finally` guarded by that flag) never ran either.
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
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
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
    fun `a failure building the foreground notification is handled -- no loop, no crash, still reschedules`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
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
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // The failure happened before setForeground was ever reached -- enteredForeground
        // stayed false, so remove() (finally-guarded by that flag) never ran.
        assertEquals(0, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    @Test
    fun `an unexpected failure updating the notification mid-loop is handled -- cleans up and reschedules`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        // Throws on the SECOND showOrUpdate call (the first tick posts normally, simulating a
        // real mid-loop failure rather than one on the very first attempt).
        val notifier = RecordingNotifier(throwOnShowCall = 2)
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()), emptyList()) }

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
}
