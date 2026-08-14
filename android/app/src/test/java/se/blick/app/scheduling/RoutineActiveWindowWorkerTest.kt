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
import kotlinx.coroutines.CompletableDeferred
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
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.RoutineOccurrenceRuntimeRepository
import se.blick.app.data.repository.RoutineOccurrenceRuntimeState
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.RoutineWorkOwnershipRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.GetDisruptionsUseCase
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.GetRankedJourneysUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.countdownMinutes
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
import java.time.Duration
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

    /** Settable, monotonically-advanceable fake -- [ScriptedRoutineRepository] defaults to
     * advancing this in lockstep with [TickingClock] to represent normal real-time passage; the
     * hard-runtime-cap tests that specifically probe wall-clock drift (NTP, user changes)
     * advance this independently of the wall clock instead, to prove the cap only ever reacts to
     * REAL (monotonic) elapsed time, never to [Clock]/[java.time.Instant]. */
    private class FakeElapsedRealtimeProvider(startMillis: Long = 0L) : ElapsedRealtimeProvider {
        var millis: Long = startMillis
        override fun elapsedRealtimeMillis(): Long = millis
    }

    /** Settable fake -- defaults to a fixed value matching every existing test's implicit
     * assumption of "no reboot happened during this run." The reboot-recovery tests change this
     * mid-test (or seed a DIFFERENT value than what a pre-seeded [RoutineOccurrenceRuntimeState]
     * carries) to simulate a device restart. */
    private class FakeBootCountProvider(var bootCount: Int = 1) : BootCountProvider {
        override fun currentBootCount(): Int = bootCount
    }

    /** In-memory fake mirroring [se.blick.app.data.repository.RoomRoutineOccurrenceRuntimeRepository]'s
     * real persistence semantics (get-or-nothing, upsert, delete) closely enough to prove
     * [RoutineActiveWindowWorker]'s own get-or-create-and-reuse / clear-on-completion logic --
     * without needing a real Room database for these worker-level tests (see
     * `RoomRoutineOccurrenceRuntimeRepositoryTest` for that). [preSeed] lets a test simulate
     * state a PREVIOUS worker instance (or a previous boot) already persisted, before the worker
     * under test even starts. */
    private class FakeRoutineOccurrenceRuntimeRepository : RoutineOccurrenceRuntimeRepository {
        private val stored = mutableMapOf<String, RoutineOccurrenceRuntimeState>()
        val clearedRoutineIds = mutableListOf<String>()
        override suspend fun get(routineId: String): RoutineOccurrenceRuntimeState? = stored[routineId]
        override suspend fun save(routineId: String, state: RoutineOccurrenceRuntimeState) {
            stored[routineId] = state
        }
        override suspend fun clear(routineId: String) {
            clearedRoutineIds += routineId
            stored.remove(routineId)
        }
        fun preSeed(routineId: String, state: RoutineOccurrenceRuntimeState) {
            stored[routineId] = state
        }
        fun peek(routineId: String): RoutineOccurrenceRuntimeState? = stored[routineId]
    }

    /** Advances [clock] by [advanceSecondsPerCall] on every [getById] call (simulating "time
     * passed since the last loop iteration") and returns whatever [routineForCall] produces for
     * that call index -- lets a test script exactly when a routine becomes disabled, is
     * deleted, or a window boundary is crossed, independent of the coroutine dispatcher's own
     * virtual time. When [elapsedRealtime] is supplied, it advances by the SAME amount every
     * call, in lockstep with [clock] -- representing normal real-time passage for BOTH the
     * wall clock and the monotonic clock together (see the hard-runtime-cap tests, which
     * deliberately desync the two instead to prove the cap tracks only the monotonic one). */
    private class ScriptedRoutineRepository(
        private val clock: TickingClock,
        private val advanceSecondsPerCall: Long = 30,
        private val elapsedRealtime: FakeElapsedRealtimeProvider? = null,
        private val routineForCall: (callIndex: Int) -> CommuteRoutine?,
    ) : RoutineRepository {
        var callCount = 0
        override fun observeAll() = throw NotImplementedError("unused by RoutineActiveWindowWorker")
        override suspend fun getById(id: String): CommuteRoutine? {
            val result = routineForCall(callCount)
            callCount++
            clock.instant = clock.instant.plusSeconds(advanceSecondsPerCall)
            elapsedRealtime?.let { it.millis += advanceSecondsPerCall * 1000 }
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

    /** Advances the shared [clock] (simulating real wall-clock time spent awaiting this fetch,
     * exactly like [ScriptedRoutineRepository]'s own per-call advance) AND [elapsedRealtime]
     * (the monotonic measurement the loop's own end-of-tick delay is computed against -- see
     * [RoutineActiveWindowWorker]'s own `tickStartElapsedRealtimeMillis` comment) by [delayMs]
     * before returning [result] -- used to prove BOTH that elapsed disruption-fetch time is
     * subtracted from the tick's subsequent delay rather than added on top of it, AND that a
     * slow disruption fetch pushes `now` forward for the second, disruption-aware render that
     * follows it. */
    private class SlowClockAdvancingDisruptionRepository(
        private val clock: TickingClock,
        private val elapsedRealtime: FakeElapsedRealtimeProvider,
        private val delayMs: Long,
        private val result: () -> List<Disruption> = { emptyList() },
    ) : DisruptionRepository {
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            delay(delayMs)
            clock.instant = clock.instant.plusMillis(delayMs)
            elapsedRealtime.millis += delayMs
            return result()
        }
    }

    /** Advances the shared [elapsedRealtime] (and genuinely suspends, via a real [delay], so
     * [kotlinx.coroutines.test.TestScope.currentTime] reflects it too) by [delayMs] before
     * returning [result] -- simulates real device time elapsing during the departures fetch
     * itself. Used to prove the tick's end-of-loop delay is computed against the WHOLE tick's
     * elapsed time (this fetch included), not just the disruptions fetch. */
    private class SlowElapsedRealtimeAdvancingDepartureRepository(
        private val elapsedRealtime: FakeElapsedRealtimeProvider,
        private val delayMs: Long,
        private val result: () -> DeparturesResult,
    ) : DepartureRepository {
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            delay(delayMs)
            elapsedRealtime.millis += delayMs
            return result()
        }
    }

    /** Advances the shared [elapsedRealtime] (and genuinely suspends) by [delayMs] on every
     * [updateWithDepartures] call before recording it -- simulates real device time spent on
     * non-network tick work (widget rendering, standing in for "cache/render/processing" more
     * broadly), to prove the whole-tick elapsed-time measurement isn't limited to network
     * fetches specifically. */
    private class SlowElapsedRealtimeAdvancingWidgetUpdater(
        private val elapsedRealtime: FakeElapsedRealtimeProvider,
        private val delayMs: Long,
    ) : RoutineWidgetUpdater {
        var updateCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            updateWithDepartures(routine, departuresState, now, disruption = null)
        }
        override suspend fun updateWithDepartures(
            routine: CommuteRoutine,
            departuresState: LiveDeparturesState,
            now: Instant,
            disruption: Disruption?,
        ) {
            updateCallCount++
            delay(delayMs)
            elapsedRealtime.millis += delayMs
        }
        override suspend fun clear() = Unit
        override suspend fun reconcile() = Unit
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
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
     * exactly once, with the right method. [disruptionsAtUpdate] records the fourth
     * ([Disruption]?) argument of every [updateWithDepartures] call, in the same order as
     * [updateCalls] — the three-argument overload forwards here with `disruption = null`, so
     * every call this worker makes lands in the same two lists regardless of which overload it
     * used. */
    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        val updateCalls = mutableListOf<Pair<CommuteRoutine, LiveDeparturesState>>()
        val disruptionsAtUpdate = mutableListOf<Disruption?>()
        val notificationsUnavailableCalls = mutableListOf<CommuteRoutine>()
        /** Every [updateWithJourneys] call's own [now] alongside the exact journey list it was
         * given — used by the exact-destination tests below to prove the worker passes the SAME
         * already-filtered list (and the same instant) here as it used for the notification. */
        val journeysUpdateCalls = mutableListOf<Pair<List<JourneyPlan>, Instant>>()
        /** Every [updateWithJourneys] call's own [fetchFailed] argument, in the same order as
         * [journeysUpdateCalls] — a separate parallel list (matching [disruptionsAtUpdate]'s own
         * existing pattern alongside [updateCalls]) rather than changing [journeysUpdateCalls]'s
         * element type, so every pre-existing destructuring assertion against it keeps compiling
         * unchanged. */
        val journeysFetchFailedAtUpdate = mutableListOf<Boolean>()
        var clearCallCount = 0
        var reconcileCallCount = 0
        override suspend fun updateWithJourneys(routine: CommuteRoutine, journeys: List<JourneyPlan>, now: Instant, fetchFailed: Boolean) {
            journeysUpdateCalls += journeys to now
            journeysFetchFailedAtUpdate += fetchFailed
        }
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            updateWithDepartures(routine, departuresState, now, disruption = null)
        }
        override suspend fun updateWithDepartures(
            routine: CommuteRoutine,
            departuresState: LiveDeparturesState,
            now: Instant,
            disruption: Disruption?,
        ) {
            updateCalls += routine to departuresState
            disruptionsAtUpdate += disruption
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
        val cancelledRoutineIds = mutableListOf<String>()
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) {
            cancelledRoutineIds += routineId
        }
        override suspend fun isActivationRunning(routineId: String): Boolean = false
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

    /** Records every [reportUnavailable] call — for proving the worker durably reports an
     * observed unavailable state before it stops, rather than relying on some other, later
     * observer to notice the same thing. */
    private class FakeNotificationRecoveryReporter : NotificationRecoveryReporter {
        var reportUnavailableCallCount = 0
        override suspend fun reportUnavailable() {
            reportUnavailableCallCount++
        }
    }

    /** Defaults to reporting the calling run as STILL the content owner, matching every
     * existing test in this file (none of which simulate a replacement mid-run) — see the
     * dedicated ownership tests below for the ones that configure [isOwnerResult] to `false`
     * to prove a superseded run's `finally`-block cleanup is correctly suppressed instead. */
    private class FakeRoutineWorkOwnershipRepository(
        private val isOwnerResult: Boolean = true,
    ) : RoutineWorkOwnershipRepository {
        val claimedIds = mutableListOf<Pair<String, String>>()
        override suspend fun claim(routineId: String, workId: String) {
            claimedIds += routineId to workId
        }
        override suspend fun isOwner(routineId: String, workId: String): Boolean = isOwnerResult
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
        notificationRecoveryReporter: NotificationRecoveryReporter = FakeNotificationRecoveryReporter(),
        routineWorkOwnershipRepository: RoutineWorkOwnershipRepository = FakeRoutineWorkOwnershipRepository(),
        routineOccurrenceRuntimeRepository: RoutineOccurrenceRuntimeRepository = FakeRoutineOccurrenceRuntimeRepository(),
        elapsedRealtimeProvider: ElapsedRealtimeProvider = FakeElapsedRealtimeProvider(),
        bootCountProvider: BootCountProvider = FakeBootCountProvider(),
        getRankedJourneys: se.blick.app.domain.usecase.GetRankedJourneysUseCase? = null,
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
                    notificationRecoveryReporter,
                    routineWorkOwnershipRepository,
                    routineOccurrenceRuntimeRepository,
                    clock,
                    deviceZoneProvider,
                    elapsedRealtimeProvider,
                    bootCountProvider,
                    getRankedJourneys = getRankedJourneys,
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
                        FakeNotificationRecoveryReporter(),
                        FakeRoutineWorkOwnershipRepository(),
                        FakeRoutineOccurrenceRuntimeRepository(),
                        Clock.systemUTC(),
                        zoneProvider,
                        FakeElapsedRealtimeProvider(),
                        FakeBootCountProvider(),
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

    // ---- Defensive daily-duration-limit re-check (see RoutineDurationValidator) ----
    //
    // Ordinary create/edit validation should already prevent a routine like this from ever
    // being saved -- this covers the defensive backstop for a routine already sitting in
    // WorkManager's own durable queue from before this limit existed, or one written by a
    // future code change or a corrupted/edited database (see doWork's own comment at this
    // check).

    @Test
    fun `a routine whose own duration exceeds the daily limit is never activated, even though its window is open`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        // 06:00-13:00 (7h) -- genuinely ActiveNow at 07:00, so without the duration check this
        // would enter the loop exactly like any other in-window routine.
        val overLimitRoutine = routine(startTime = LocalTime.of(6, 0), endTime = LocalTime.of(13, 0))
        val repository = ScriptedRoutineRepository(clock) { overLimitRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            overLimitRoutine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Never enters foreground execution -- no notification posted, no loop, no remove().
        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        // Cancelled directly, not rescheduled via scheduleActivation -- WorkManagerRoutineScheduler
        // would just reject it again anyway.
        assertTrue(scheduler.scheduledRoutines.isEmpty())
        assertEquals(listOf(overLimitRoutine.id), scheduler.cancelledRoutineIds)
        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    // ---- Hard real-elapsed-time safety cap (see HARD_FOREGROUND_RUNTIME_CAP_MINUTES's own doc)
    // -- independent of windowEnd, which can diverge from real elapsed time on a DST transition
    // ----

    @Test
    fun `autumn DST -- a 5-hour local window spans 6 real hours, but the flat 330-minute cap stops it first`() = runTest {
        // 2027-10-31 is a real Stockholm autumn DST transition (clocks fall back from 03:00 CEST
        // to 02:00 CET) -- verified directly via java.time: a configured "00:00-05:00" window
        // (300 local minutes -- exactly MAX_DAILY_ACTIVE_MINUTES) on this specific Sunday spans a
        // REAL 360 minutes (6 hours), from instant 2027-10-30T22:00:00Z to 2027-10-31T04:00:00Z.
        // Autumn never shortens the gap to the NEXT occurrence (see
        // EffectiveHardCapMinutesTest's own autumn test), so this occurrence's effective cap
        // stays the full, unreduced HARD_FOREGROUND_RUNTIME_CAP_MINUTES -- proving that constant
        // ALONE (no DST-specific reduction needed here, exactly as its own doc describes) stops
        // this run 30 real minutes before its own natural, DST-extended end.
        val stockholm = ZoneId.of("Europe/Stockholm")
        val stockholmZoneProvider = DeviceZoneProvider { stockholm }
        val windowStartInstant = Instant.parse("2027-10-30T22:00:00Z")
        val naturalWindowEndInstant = Instant.parse("2027-10-31T04:00:00Z") // verified: real 360min later
        val clock = TickingClock(windowStartInstant, stockholm)
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        val dstRoutine = routine(startTime = LocalTime.of(0, 0), endTime = LocalTime.of(5, 0))
            .copy(activeDays = setOf(DayOfWeek.SUNDAY))
        // 10 real minutes/tick -- elapsedRealtime advances in lockstep with clock here, both
        // representing NORMAL real-time passage together, unlike the wall-clock-drift test
        // below, which deliberately desyncs them.
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 600, elapsedRealtime = elapsedRealtime) { dstRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            dstRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            deviceZoneProvider = stockholmZoneProvider,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Stopped well before the DST-extended natural end (360 real minutes in)...
        assertTrue(clock.instant.isBefore(naturalWindowEndInstant))
        // ...specifically via the hard cap tracking real elapsed time, not the local-clock
        // windowEnd, which this DST-extended occurrence would not have reached for another 30
        // real minutes.
        assertTrue(elapsedRealtime.millis >= Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis())
        assertEquals(LocalDate.of(2027, 10, 31), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `a wall clock jump backward while the worker runs does not affect the hard cap's timing`() = runTest {
        // A plain, non-DST 5-hour routine -- the wall clock is yanked backward by 5 hours mid-run
        // (simulating the user changing the device clock, or an NTP correction). A backward jump
        // only ever makes "is the window still open" (windowEnd, wall-clock-based) look MORE
        // true, never less -- so it makes windowEnd permanently unreachable for the rest of this
        // run, and only the hard cap, tracking elapsedRealtime (deliberately advanced in lockstep
        // with the clock here, desynced only by the one jump below), can be what stops the loop.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0)) // 5h
        val baseRepository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 600, elapsedRealtime = elapsedRealtime) { testRoutine }
        var callCount = 0
        val repository = object : RoutineRepository by baseRepository {
            override suspend fun getById(id: String): CommuteRoutine? {
                val result = baseRepository.getById(id)
                callCount++
                if (callCount == 5) {
                    // A one-time backward jump, well after the loop has already started --
                    // elapsedRealtime is untouched by this.
                    clock.instant = clock.instant.minusSeconds(5 * 3600)
                }
                return result
            }
        }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // The hard cap still fired based on elapsedRealtime, unaffected by the wall-clock jump --
        // without it, the backward jump would make this run continue indefinitely, since
        // windowEnd (wall-clock-based) can never be reached again.
        assertTrue(elapsedRealtime.millis >= Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    // ---- Exact boundary: 329min proceeds, 330min stops (short window, no DST involved -- the
    // routine's own natural end is far enough away that only the hard cap can be what fires) ----

    @Test
    fun `329 minutes of real elapsed time does not stop the loop`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val testRoutine = routine() // 07:00-07:02
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = 0L,
                bootCountAtStart = 1,
                hardStopEpochMilli = Long.MAX_VALUE, // irrelevant here -- same boot, monotonic path used
            ),
        )
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(329).toMillis())
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        assertTrue("expected the loop to proceed normally at 329 minutes", notifier.shown.isNotEmpty())
    }

    @Test
    fun `an occurrence already at exactly 330 minutes of real elapsed time is detected as exhausted before foreground even starts`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val ownershipRepository = FakeRoutineWorkOwnershipRepository()
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = 0L,
                bootCountAtStart = 1,
                hardStopEpochMilli = Long.MAX_VALUE,
            ),
        )
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis())
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Caught by doWork's own pre-foreground read -- setForeground is never even called, so
        // nothing was ever posted, content ownership was never claimed, and there is nothing to
        // remove.
        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertTrue(ownershipRepository.claimedIds.isEmpty())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
        // rescheduleSkippingToday -- not a plain rescheduleNext, which would recompute the SAME
        // still-open occurrence and cause WorkManagerRoutineScheduler to enqueue it with a zero
        // initial delay, spinning this exact same exhaustion check in a tight loop.
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    // ---- Reboot fallback and cross-instance continuation ----

    @Test
    fun `a reboot does not grant a fresh 330-minute allowance`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        // The device rebooted since this occurrence started -- elapsedRealtime has reset to a
        // fresh, low value, and the persisted bootCountAtStart (1) no longer matches the
        // CURRENT boot (2).
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(2).toMillis())
        val bootCountProvider = FakeBootCountProvider(bootCount = 2)
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        // hardStopEpochMilli was computed from the wall clock BEFORE the reboot, and that
        // threshold has already passed by the time this post-reboot worker runs.
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = 0L,
                bootCountAtStart = 1,
                hardStopEpochMilli = clock.instant.toEpochMilli() - 1,
            ),
        )
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
            bootCountProvider = bootCountProvider,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Stopped immediately despite elapsedRealtime showing only 2 minutes since boot -- the
        // reboot fallback (wall-clock hardStopEpochMilli, already past) correctly refused to
        // grant a fresh allowance just because the monotonic clock reset.
        assertTrue(notifier.shown.isEmpty())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `a replacement worker starting after four hours has only 90 minutes remaining, not a fresh 330`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        // A long (4h, still within validateSelf's own limit), plain non-DST window -- its
        // natural end is comfortably far away, so only the hard cap (using the pre-seeded,
        // reused start below) can be what stops this run within the few ticks this test needs.
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(11, 0))
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val bootCountProvider = FakeBootCountProvider(bootCount = 1)
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        val originalStartMillis = 1_000_000L
        // An earlier (now-dead) worker instance already ran for 4 hours before being replaced --
        // same boot, so the monotonic comparison path applies.
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = originalStartMillis,
                bootCountAtStart = 1,
                hardStopEpochMilli = Long.MAX_VALUE,
            ),
        )
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = originalStartMillis + Duration.ofHours(4).toMillis())
        // 30 real minutes per tick from here.
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 1800, elapsedRealtime = elapsedRealtime) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
            bootCountProvider = bootCountProvider,
        )
        worker.doWork()

        // Only ~90 more minutes of REAL elapsed time were needed before the cap fired -- proving
        // the replacement worker continued the ORIGINAL 330-minute budget rather than resetting
        // it: elapsedRealtime lands close to originalStartMillis + 330min, nowhere near
        // originalStartMillis + 4h + 330min (what a wrongly-reset "fresh allowance" would need).
        val elapsedSinceOriginalStart = elapsedRealtime.millis - originalStartMillis
        assertTrue(elapsedSinceOriginalStart >= Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis())
        // Comfortably under a FRESH 330-minute allowance measured from the 4-hour mark (4h +
        // 330min = 570min) -- proving the cap fired around the ORIGINAL 330-minute mark, not a
        // reset one, while still allowing headroom for this test's own extra pre-loop tick.
        assertTrue(elapsedSinceOriginalStart < Duration.ofHours(4).toMillis() + Duration.ofMinutes(200).toMillis())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `a process restart on the same boot continues counting from the originally persisted start, not a fresh one`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine()
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val bootCountProvider = FakeBootCountProvider(bootCount = 1)
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        val originalStartMillis = 12_345_678L // an arbitrary, non-zero reference
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = originalStartMillis,
                bootCountAtStart = 1, // same boot as the "restarted process" below
                hardStopEpochMilli = Long.MAX_VALUE,
            ),
        )
        // This "restarted process" instance's OWN current elapsedRealtime reading is already
        // exactly HARD_FOREGROUND_RUNTIME_CAP_MINUTES past the ORIGINAL persisted start. If that
        // original start is correctly reused, the cap must fire immediately, on the very first
        // tick -- if a bug instead reset monotonicStart to THIS worker's own current reading (a
        // fresh zero-point), elapsed-since-start would wrongly read as zero and never fire here.
        val elapsedRealtime = FakeElapsedRealtimeProvider(
            startMillis = originalStartMillis + Duration.ofMinutes(HARD_FOREGROUND_RUNTIME_CAP_MINUTES).toMillis(),
        )
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
            bootCountProvider = bootCountProvider,
        )
        worker.doWork()

        assertTrue("expected the ORIGINAL persisted start to be reused, stopping immediately", notifier.shown.isEmpty())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    // ---- Effective (possibly DST-reduced) cap survives reboot/replacement identically to the
    // flat 330-minute one -- see EffectiveHardCapMinutesTest for the pure-function arithmetic ----

    @Test
    fun `a normal, non-DST-adjacent 5-hour occurrence is freshly established with the full, unreduced 330-minute cap`() = runTest {
        // A plain Monday-only routine -- its own next occurrence is a full, ordinary week away,
        // nowhere near any DST transition, so effectiveHardCapMinutes must not reduce anything.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0)) // 5h
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(42).toMillis())
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        val job = launch { worker.doWork() }
        runCurrent() // let the first tick run up to its delay(30s) suspension point
        job.cancel()
        job.join()

        // No reduction was applied: the persisted start exactly matches elapsedRealtime's own
        // value at the moment foreground execution began (42 minutes -- unchanged from its
        // starting value, since advanceSecondsPerCall = 0 here), proving
        // createFreshOccurrenceRuntimeState did not shift it earlier at all.
        val established = runtimeRepository.peek(testRoutine.id)
        assertEquals(Duration.ofMinutes(42).toMillis(), established?.monotonicStartElapsedRealtimeMillis)
    }

    @Test
    fun `a reboot after a DST-reduced occurrence started respects the REDUCED 270-minute cap, not the flat 330`() = runTest {
        val stockholm = ZoneId.of("Europe/Stockholm")
        val stockholmZoneProvider = DeviceZoneProvider { stockholm }
        // Saturday 27 March 2027 07:00-12:00 (5h) -- the occurrence immediately before the
        // Stockholm spring transition, whose effective cap createFreshOccurrenceRuntimeState
        // computes as 270 minutes (see EffectiveHardCapMinutesTest for the exact arithmetic):
        // true start 2027-03-27T06:00:00Z, so its reduced hard stop is 2027-03-27T10:30:00Z --
        // 06:00Z + 270min -- rather than the flat cap's 2027-03-27T11:30:00Z -- 06:00Z + 330min.
        val dstRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0))
            .copy(activeDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val bootCountProvider = FakeBootCountProvider(bootCount = 1)

        // First worker instance: establishes this occurrence's runtime state for real (a genuine
        // fresh creation, exercising effectiveHardCapMinutes), then is torn down mid-run --
        // simulating the process being killed -- before ever reaching its own hard cap or
        // natural end.
        run {
            val clock = TickingClock(Instant.parse("2027-03-27T06:00:00Z"), stockholm) // 07:00 local
            val elapsedRealtime = FakeElapsedRealtimeProvider()
            val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0, elapsedRealtime = elapsedRealtime) { dstRoutine }
            val notifier = RecordingNotifier()
            val scheduler = RecordingScheduler()
            val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
            val worker = buildWorker(
                dstRoutine.id,
                repository,
                GetLiveDeparturesUseCase(departures, clock),
                notifier,
                scheduler,
                clock,
                deviceZoneProvider = stockholmZoneProvider,
                routineOccurrenceRuntimeRepository = runtimeRepository,
                elapsedRealtimeProvider = elapsedRealtime,
                bootCountProvider = bootCountProvider,
            )
            val job = launch { worker.doWork() }
            runCurrent()
            job.cancel()
            job.join()
        }
        assertTrue("expected the first run to have established runtime state", runtimeRepository.peek(dstRoutine.id) != null)

        // Second worker instance: a different boot (the device rebooted), wall clock now sitting
        // BETWEEN the REDUCED (270min -> 10:30Z) and the flat (330min -> 11:30Z) thresholds, and
        // still strictly before Saturday's own natural windowEnd (12:00 local -> 11:00Z, so
        // NextOccurrenceCalculator still reports this occurrence as ActiveNow rather than already
        // elapsed) -- only reported as exhausted here if the reduction survived the reboot.
        val postRebootClock = TickingClock(Instant.parse("2027-03-27T10:45:00Z"), stockholm)
        val postRebootElapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(2).toMillis())
        val postRebootBootCountProvider = FakeBootCountProvider(bootCount = 2)
        val postRebootNotifier = RecordingNotifier()
        val postRebootScheduler = RecordingScheduler()
        val postRebootRepository = ScriptedRoutineRepository(postRebootClock, elapsedRealtime = postRebootElapsedRealtime) { dstRoutine }
        val postRebootDepartures = FakeDepartureRepository { DeparturesResult(postRebootClock.instant(), 9145, listOf(sampleDeparture())) }
        val postRebootWorker = buildWorker(
            dstRoutine.id,
            postRebootRepository,
            GetLiveDeparturesUseCase(postRebootDepartures, postRebootClock),
            postRebootNotifier,
            postRebootScheduler,
            postRebootClock,
            deviceZoneProvider = stockholmZoneProvider,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = postRebootElapsedRealtime,
            bootCountProvider = postRebootBootCountProvider,
        )
        val result = postRebootWorker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Never entered foreground -- the reboot-fallback wall-clock check (using the SAME
        // reduced 270-minute effective cap the first run computed) already shows this occurrence
        // exhausted, at a wall-clock instant that would NOT yet have tripped the flat 330-minute
        // cap.
        assertTrue(postRebootNotifier.shown.isEmpty())
        assertEquals(0, postRebootNotifier.removeCallCount)
        assertEquals(LocalDate.of(2027, 3, 27), postRebootScheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `the next genuine occurrence replaces a left-in-place exhausted state normally`() = runTest {
        // Continues the reboot scenario above -- once Sunday's OWN occurrence becomes eligible
        // (a genuinely different windowEnd), createFreshOccurrenceRuntimeState must overwrite the
        // stale, still-exhausted Saturday row rather than being confused by it.
        val stockholm = ZoneId.of("Europe/Stockholm")
        val stockholmZoneProvider = DeviceZoneProvider { stockholm }
        val dstRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0))
            .copy(activeDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val staleSaturdayIdentity = ZonedDateTime.of(LocalDate.of(2027, 3, 27), dstRoutine.endTime, stockholm).toInstant().toEpochMilli()
        // Saturday's occurrence already exhausted its (reduced, 270min) cap and was correctly
        // left in place (see the hard-cap-does-not-clear tests above) -- simulating that Sunday
        // now starts completely independently, in a fresh process.
        runtimeRepository.preSeed(
            dstRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = staleSaturdayIdentity,
                monotonicStartElapsedRealtimeMillis = -Duration.ofMinutes(60).toMillis(),
                bootCountAtStart = 1,
                hardStopEpochMilli = Instant.parse("2027-03-27T10:30:00Z").toEpochMilli(),
            ),
        )

        val clock = TickingClock(Instant.parse("2027-03-28T05:00:00Z"), stockholm) // Sunday 07:00 local
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(500).toMillis())
        val bootCountProvider = FakeBootCountProvider(bootCount = 1)
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0, elapsedRealtime = elapsedRealtime) { dstRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            dstRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            deviceZoneProvider = stockholmZoneProvider,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
            bootCountProvider = bootCountProvider,
        )
        val job = launch { worker.doWork() }
        runCurrent()

        // Sunday's occurrence runs normally -- the stale Saturday row (a different windowEnd) did
        // not block it at all.
        assertTrue("expected Sunday's occurrence to run normally despite Saturday's stale exhausted row", notifier.shown.isNotEmpty())
        val sundayIdentity = ZonedDateTime.of(LocalDate.of(2027, 3, 28), dstRoutine.endTime, stockholm).toInstant().toEpochMilli()
        assertEquals(sundayIdentity, runtimeRepository.peek(dstRoutine.id)?.occurrenceWindowEndEpochMilli)

        job.cancel()
        job.join()
    }

    // ---- Fail-safe: a runtime-state READ failure must never be treated as "nothing exists,
    // safe to start fresh" -- see RoutineActiveWindowWorker.doWork's own comment ----

    @Test
    fun `a runtime-state read failure before foreground refuses to grant a fresh allowance`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0)) // 5h
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val ownershipRepository = FakeRoutineWorkOwnershipRepository()
        val failingRuntimeRepository = object : RoutineOccurrenceRuntimeRepository {
            override suspend fun get(routineId: String): RoutineOccurrenceRuntimeState = throw IOException("Room unavailable")
            override suspend fun save(routineId: String, state: RoutineOccurrenceRuntimeState) {
                throw AssertionError("must never be reached -- doWork should return before any save is attempted")
            }
            override suspend fun clear(routineId: String) {
                throw AssertionError("must never be reached -- doWork should return before any clear is attempted")
            }
        }
        val departures = FakeDepartureRepository { error("unused -- the loop must never be entered") }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = failingRuntimeRepository,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Never entered foreground -- a read failure is treated with the same caution as
        // "possibly already exhausted," not optimistically as "definitely fresh."
        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertTrue(ownershipRepository.claimedIds.isEmpty())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    @Test
    fun `a fresh occurrence's runtime-state SAVE failure before foreground refuses to grant a fresh allowance`() = runTest {
        // Nothing persisted yet (a genuinely new occurrence) -- get() succeeds and returns null,
        // but the subsequent save() of the freshly-computed state fails. This must be refused
        // exactly like the read-failure case above: a fresh occurrence's safety record must be
        // DURABLY persisted BEFORE foreground execution, not used in-memory-only, or a
        // replacement worker/reboot later finding nothing in Room would wrongly treat this same
        // occurrence as never having started and grant it a second, unbounded allowance.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0)) // 5h
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val ownershipRepository = FakeRoutineWorkOwnershipRepository()
        var saveAttempted = false
        val failingSaveRepository = object : RoutineOccurrenceRuntimeRepository {
            override suspend fun get(routineId: String): RoutineOccurrenceRuntimeState? = null
            override suspend fun save(routineId: String, state: RoutineOccurrenceRuntimeState) {
                saveAttempted = true
                throw IOException("Room unavailable")
            }
            override suspend fun clear(routineId: String) {
                throw AssertionError("must never be reached -- doWork should return before any clear is attempted")
            }
        }
        val departures = FakeDepartureRepository { error("unused -- the loop must never be entered") }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = failingSaveRepository,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("expected the save to have actually been attempted", saveAttempted)
        // Never entered foreground -- setForeground is called AFTER the fresh state is durably
        // saved, not before, so a save failure here is caught in time to refuse foreground
        // entirely rather than proceeding on an in-memory-only budget.
        assertTrue(notifier.shown.isEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertTrue(ownershipRepository.claimedIds.isEmpty())
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
        assertNoZeroDelayReschedule(scheduler, clock)
    }

    // ---- Clearing persisted runtime state once an occurrence is over ----

    @Test
    fun `occurrence runtime state is NOT cleared when the loop itself hits the hard cap`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val testRoutine = routine(startTime = LocalTime.of(7, 0), endTime = LocalTime.of(8, 0)) // 1h -- room for a few ticks
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val occurrenceIdentity = ZonedDateTime.of(LocalDate.of(2026, 7, 27), testRoutine.endTime, zone).toInstant().toEpochMilli()
        runtimeRepository.preSeed(
            testRoutine.id,
            RoutineOccurrenceRuntimeState(
                occurrenceWindowEndEpochMilli = occurrenceIdentity,
                monotonicStartElapsedRealtimeMillis = 0L,
                bootCountAtStart = 1,
                hardStopEpochMilli = Long.MAX_VALUE,
            ),
        )
        // Starts at 327 minutes -- still under the cap by the time doWork's own pre-foreground
        // check reads it (after the one, unavoidable tick from fetching the routine itself,
        // before this check even runs), and still under it for the loop's own first tick (so at
        // least one notification posts, proving the loop genuinely ran) -- but crosses the cap on
        // the loop's SECOND tick. This test genuinely exercises the loop's own hitHardRuntimeCap
        // path, not the pre-foreground one (see the dedicated pre-foreground exhaustion test
        // elsewhere in this file for that case).
        val elapsedRealtime = FakeElapsedRealtimeProvider(startMillis = Duration.ofMinutes(327).toMillis())
        val repository = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 60, elapsedRealtime = elapsedRealtime) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        // The loop did genuinely run (proving the pre-foreground check let it through)...
        assertTrue("expected at least one tick to post before the cap fired", notifier.shown.isNotEmpty())
        // ...but once it hit the cap, its record was left in place rather than cleared -- so a
        // replacement worker or reboot-recovered process can still see this occurrence is
        // exhausted (see the dedicated reboot/replacement tests for that).
        assertTrue(runtimeRepository.clearedRoutineIds.isEmpty())
        assertEquals(occurrenceIdentity, runtimeRepository.peek(testRoutine.id)?.occurrenceWindowEndEpochMilli)
        assertEquals(LocalDate.of(2026, 7, 27), scheduler.scheduledRoutines.single().pausedDate)
    }

    @Test
    fun `occurrence runtime state IS cleared on a normal natural-end completion`() = runTest {
        // Contrast with the test above -- clearing is only skipped specifically because the hard
        // cap fired; every OTHER ending still clears normally, exactly as before.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val testRoutine = routine() // 07:00-07:02
        val repository = ScriptedRoutineRepository(clock) { testRoutine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val runtimeRepository = FakeRoutineOccurrenceRuntimeRepository()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            testRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            routineOccurrenceRuntimeRepository = runtimeRepository,
        )
        worker.doWork()

        assertEquals(listOf(testRoutine.id), runtimeRepository.clearedRoutineIds)
        assertNull(runtimeRepository.peek(testRoutine.id))
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
        val recoveryReporter = FakeNotificationRecoveryReporter()

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused") }, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
            notificationRecoveryReporter = recoveryReporter,
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
        // Durably records that a recovery attempt is owed BEFORE this run stops -- see
        // NotificationRecoveryCoordinator's own doc for why this must happen here rather than
        // being inferred later from a compared availability snapshot.
        assertEquals(1, recoveryReporter.reportUnavailableCallCount)
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
        val recoveryReporter = FakeNotificationRecoveryReporter()

        val worker = buildWorker(
            enabledRoutine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            notificationAvailabilityChecker = checker,
            widgetUpdater = widgetUpdater,
            notificationRecoveryReporter = recoveryReporter,
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
        // Discovered mid-loop, not just at startup -- must still be durably recorded before this
        // run stops, exactly like the pre-loop check.
        assertEquals(1, recoveryReporter.reportUnavailableCallCount)
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
    fun `a failure building the foreground notification after a successful ownership claim is handled -- no loop, no crash, still reschedules, ownership-aware cleanup runs`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val scheduler = RecordingScheduler()
        val widgetUpdater = RecordingWidgetUpdater()
        val ownershipRepository = FakeRoutineWorkOwnershipRepository()
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
            routineWorkOwnershipRepository = ownershipRepository,
        )
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isEmpty())
        // Ownership is claimed BEFORE setForeground is ever attempted -- this run claimed it
        // successfully even though building the foreground notification (part of the very next
        // statement) then failed. See the class doc's "if ownership succeeds but setForeground
        // subsequently fails, safely clean/reconcile" paragraph -- this is exactly that case.
        assertEquals(listOf(routine.id to worker.id.toString()), ownershipRepository.claimedIds)
        // Since ownership was already claimed, the `finally` block's cleanup is no longer gated
        // on setForeground having succeeded -- it runs unconditionally (subject only to the
        // ownership check, which reports true here, the default), removing whatever content may
        // exist and clearing the widget, exactly like any other handled-failure exit.
        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
        assertEquals(0, widgetUpdater.reconcileCallCount)
        assertEquals(1, widgetUpdater.clearCallCount)
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
    fun `a newly-discovered disruption also triggers exactly one extra silent widget update, mirroring the notification`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val disruptions = FakeDisruptionRepository { listOf(sampleDisruption()) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
            getDisruptions = GetDisruptionsUseCase(disruptions),
        )
        worker.doWork()

        // Same reasoning as the notification's own identical test just above: tick 1 updates the
        // widget once with no disruption yet known, then once more when this tick's own fetch
        // discovers one; tick 2 already knows about it from the start, so its own single update
        // already carries it and nothing changes at the end of that tick -- three widget updates
        // total, not two, not four.
        assertEquals(3, widgetUpdater.updateCalls.size)
        assertEquals(listOf(null, sampleDisruption(), sampleDisruption()), widgetUpdater.disruptionsAtUpdate)
    }

    @Test
    fun `no disruption ever found means every widget update carries a null disruption`() = runTest {
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

        assertTrue(widgetUpdater.updateCalls.isNotEmpty())
        assertTrue(widgetUpdater.disruptionsAtUpdate.all { it == null })
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
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        // Well under DISRUPTIONS_FETCH_TIMEOUT_MS (5s), so this always resolves rather than
        // timing out -- but still genuinely slow enough to matter if its elapsed time were
        // simply added on top of ACTIVE_WINDOW_REFRESH_INTERVAL_MS instead of subtracted from it.
        val disruptions = SlowClockAdvancingDisruptionRepository(clock, elapsedRealtime, delayMs = 3_000L)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        // Without the fix, each tick's delay would still be the full ACTIVE_WINDOW_REFRESH_INTERVAL_MS
        // on top of the 3s already spent fetching, drifting total tick spacing to 2 * 33s = 66s.
        // With the fix, each tick's delay is shortened to 27s, so the two ticks' combined virtual
        // time (3s fetch + 27s delay, twice) lands exactly on 2 * ACTIVE_WINDOW_REFRESH_INTERVAL_MS.
        assertEquals(2, notifier.shown.size)
        assertEquals(2 * ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }

    // ---- Requirement: the ~30s cadence measures the WHOLE tick -- the routine re-read,
    // departures fetch, caching, notification/widget rendering, and the disruptions fetch --
    // not just the disruptions fetch alone (Fix: tickStartElapsedRealtimeMillis captured before
    // any of a tick's own work; the end-of-tick delay is computed against
    // elapsedRealtimeProvider, never wall-clock time) ----

    @Test
    fun `a tick that takes 0ms delays for approximately the full 30 seconds`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine()
        // Exactly one tick: the pre-loop getById (call 0) and the loop's own first getById
        // (call 1) both return the routine; the loop's SECOND getById (call 2) returns null,
        // ending the loop right after tick 1 completes -- so currentTime after doWork() reflects
        // exactly one tick's own delay, not two ticks' worth.
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
        )
        worker.doWork()

        // A plain FakeDepartureRepository never touches elapsedRealtimeProvider, and nothing
        // else in this tick does either -- the whole tick's own measured elapsed time is 0ms, so
        // the delay that follows it should be the full interval.
        assertEquals(ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }

    @Test
    fun `a tick that takes 10 seconds delays for approximately the remaining 20 seconds`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        val departures = SlowElapsedRealtimeAdvancingDepartureRepository(elapsedRealtime, delayMs = 10_000L) {
            DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()))
        }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        // 10s (the fetch's own real delay) + 20s (the computed remaining delay) = 30s total.
        assertEquals(ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }

    @Test
    fun `a tick that takes 29 seconds delays for approximately the remaining 1 second`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        val departures = SlowElapsedRealtimeAdvancingDepartureRepository(elapsedRealtime, delayMs = 29_000L) {
            DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()))
        }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        // 29s (the fetch's own real delay) + 1s (the computed remaining delay) = 30s total.
        assertEquals(ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }

    @Test
    fun `a tick that takes at least 30 seconds adds no extra interval delay`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        // Comfortably >= ACTIVE_WINDOW_REFRESH_INTERVAL_MS on its own.
        val tickDurationMs = 35_000L
        val departures = SlowElapsedRealtimeAdvancingDepartureRepository(elapsedRealtime, delayMs = tickDurationMs) {
            DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()))
        }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        // No extra delay tacked on top -- total virtual time elapsed is exactly the tick's own
        // duration (coerceAtLeast(0L) keeps the computed delay itself at zero, not negative),
        // not tickDurationMs + ACTIVE_WINDOW_REFRESH_INTERVAL_MS.
        assertEquals(tickDurationMs, currentTime)
    }

    @Test
    fun `time spent rendering the widget update also counts toward the tick's measured elapsed time`() = runTest {
        // Proves the whole-tick measurement isn't limited to network fetches -- any of a tick's
        // own suspending work (widget/Glance rendering here, standing in for "cache/render/
        // processing" more broadly) counts too, through the same ElapsedRealtimeProvider seam.
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val widgetUpdater = SlowElapsedRealtimeAdvancingWidgetUpdater(elapsedRealtime, delayMs = 12_000L)

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        assertEquals(1, widgetUpdater.updateCallCount)
        // 12s (widget rendering) + 18s (the computed remaining delay) = 30s total.
        assertEquals(ACTIVE_WINDOW_REFRESH_INTERVAL_MS, currentTime)
    }

    // ---- Regression: the disruption-aware second render must use a FRESH clock reading, not
    // the `now` captured before the disruptions fetch even started (Fix: a new clock.instant()
    // is captured immediately before building/rendering that second update) ----

    @Test
    fun `the disruption-aware second render recomputes the countdown from a fresh clock reading, not the stale pre-fetch one`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { callIndex -> if (callIndex < 2) routine else null }
        val notifier = RecordingNotifier()
        val elapsedRealtime = FakeElapsedRealtimeProvider()
        // Tick 1's own `now` lands at 05:01:00Z (see the class doc's worked example). This
        // departure is 182s away at that instant -- (182 + 59) / 60 = 4 minutes remaining.
        val departure = sampleDeparture().copy(scheduledTime = Instant.parse("2026-07-27T05:04:02Z"))
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(departure)) }
        // Well under DISRUPTIONS_FETCH_TIMEOUT_MS (5s), so this resolves rather than timing out;
        // returns a genuinely new disruption so the second, disruption-aware render actually
        // fires. Advances the clock by 3s -- enough to cross the 3-minute countdown boundary
        // (182s - 3s = 179s -- (179 + 59) / 60 = 3 minutes remaining) but nowhere near the 5s
        // disruptions timeout.
        val disruptions = SlowClockAdvancingDisruptionRepository(clock, elapsedRealtime, delayMs = 3_000L) { listOf(sampleDisruption()) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            getDisruptions = GetDisruptionsUseCase(disruptions),
            elapsedRealtimeProvider = elapsedRealtime,
        )
        worker.doWork()

        assertEquals(2, notifier.shown.size)
        val firstMinutesRemaining = (notifier.shown[0].content as RoutineNotificationContent.Live).departures.single().minutesRemaining
        val secondMinutesRemaining = (notifier.shown[1].content as RoutineNotificationContent.Live).departures.single().minutesRemaining
        assertEquals(4L, firstMinutesRemaining)
        // Without the fix, the second render would reuse the SAME stale `now` as the first,
        // showing 4 minutes remaining again despite the disruptions fetch having genuinely taken
        // 3 real seconds in between.
        assertEquals(3L, secondMinutesRemaining)
    }

    // ---- Requirement: a departures-fetch timeout (an OkHttp whole-call-timeout-shaped
    // IOException) follows the existing failure/stale-data path, without killing the active
    // window early -- see NetworkModule.CALL_TIMEOUT_MS's own doc for the timeout itself ----

    @Test
    fun `a departures fetch that fails with a timeout-shaped IOException preserves stale data without ending the window early`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        var callCount = 0
        val departures = object : DepartureRepository {
            override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
                callCount++
                if (callCount == 1) return DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture()))
                // The exact exception type OkHttp's own call timeout throws -- a plain
                // IOException("network down") elsewhere in this file already proves the general
                // failure/stale-data path, but this specifically proves a TIMEOUT (not just any
                // I/O failure) is caught by that same existing path, since InterruptedIOException
                // is itself an IOException subtype.
                throw java.io.InterruptedIOException("timeout")
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
        // Falls back to the previous successful snapshot as Stale, exactly like any other
        // connectivity-shaped failure -- the timeout does not end the active window early (the
        // loop still ran its normal two ticks) and does not surface as Unavailable/a crash.
        assertTrue(notifier.shown[1].content is RoutineNotificationContent.Stale)
        assertEquals(1, notifier.removeCallCount)
    }

    // ---- Content ownership (Fix: generation/ownership tracking gates `finally` cleanup, so a
    // cancelled/replaced run can never clobber a newer run's own content) ----

    @Test
    fun `this run claims content ownership under its own WorkManager id before foreground execution begins`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val ownershipRepository = FakeRoutineWorkOwnershipRepository()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            RecordingScheduler(),
            clock,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        worker.doWork()

        assertEquals(listOf(routine.id to worker.id.toString()), ownershipRepository.claimedIds)
    }

    @Test
    fun `the current owner still cleans up normally at window end`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingScheduler()
        // isOwnerResult = true (the default) -- this run is still the recorded owner of
        // whatever it posted, so its own `finally` must clean up exactly as before; this is
        // deliberately NOT "skip cleanup on every cancellation/completion" -- only a genuine
        // loss of ownership suppresses it (see the two tests below).
        val ownershipRepository = FakeRoutineWorkOwnershipRepository(isOwnerResult = true)
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        worker.doWork()

        assertEquals(1, notifier.removeCallCount)
        assertEquals(1, widgetUpdater.clearCallCount)
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    @Test
    fun `a replaced worker cannot clear a replacement's content once it has lost ownership`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        val repository = ScriptedRoutineRepository(clock) { routine }
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingScheduler()
        // Simulates a REPLACEMENT run having already claimed ownership for this routine by the
        // time THIS run's own `finally` block checks -- e.g. this run was cancelled and
        // immediately superseded by a new run for the same routine id (editing an active
        // routine cancels the old worker and immediately starts a new one).
        val ownershipRepository = FakeRoutineWorkOwnershipRepository(isOwnerResult = false)
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }

        val worker = buildWorker(
            routine.id,
            repository,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
            routineWorkOwnershipRepository = ownershipRepository,
        )
        val result = worker.doWork()

        // The loop still ran and posted content normally -- ownership only gates CLEANUP, never
        // the run itself -- but since this run no longer owns what it posted, its own `finally`
        // must never remove the notification or clear the widget: a replacement run may already
        // be actively showing its own content for this same routine.
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.shown.isNotEmpty())
        assertEquals(0, notifier.removeCallCount)
        assertEquals(0, widgetUpdater.clearCallCount)
        // Rescheduling is unaffected by ownership -- this run still reschedules the next
        // eligible occurrence regardless of whether it owned the content it posted.
        assertEquals(1, scheduler.scheduledRoutines.size)
    }

    // ---- Deterministic regression for the actual claim-before-setForeground handoff race:
    // claim() now happens BEFORE setForeground()/any content is posted, so a concurrently-
    // finishing old run can never observe itself as owner during the window where a replacement
    // has already claimed but not yet published anything of its own. ----

    /** A real (in-memory, stateful) ownership store shared between two concurrently-driven
     * worker runs -- unlike [FakeRoutineWorkOwnershipRepository]'s fixed [isOwnerResult],
     * [isOwner] here reflects whichever run's [claim] landed most recently, exactly like the
     * real [se.blick.app.data.repository.RoomRoutineWorkOwnershipRepository]. The SECOND-EVER
     * [claim] call specifically writes its new owner FIRST, then suspends on [gate] before
     * returning -- so a test can observe ownership having genuinely transferred to the
     * replacement run while that SAME run is still blocked, before it can post any content of
     * its own (the first claim call, from the already-running "owns content" run, is never
     * gated, so it can finish claiming and posting before the test starts the replacement). */
    private class SecondClaimGatedOwnershipRepository(
        private val gate: CompletableDeferred<Unit>,
    ) : RoutineWorkOwnershipRepository {
        private var currentOwner: String? = null
        private var claimCount = 0
        override suspend fun claim(routineId: String, workId: String) {
            claimCount++
            currentOwner = workId
            if (claimCount == 2) gate.await()
        }
        override suspend fun isOwner(routineId: String, workId: String): Boolean = currentOwner == workId
    }

    @Test
    fun `an old worker mid-loop cannot clear shared content once a replacement has claimed ownership, even before the replacement has posted anything of its own`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone)
        val routine = routine()
        // SHARED between both runs -- this is the "notification/widget content" the race is
        // about: whichever run's cleanup fires must never clear content actually owned by the
        // other.
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingScheduler()
        val departures = FakeDepartureRepository { DeparturesResult(clock.instant(), 9145, listOf(sampleDeparture())) }
        val gate = CompletableDeferred<Unit>()
        val sharedOwnership = SecondClaimGatedOwnershipRepository(gate)

        // A -- already running: claims ownership (the ungated first claim), posts its first
        // tick's content, then sits in its 30s inter-tick delay. This is "A owns content."
        // advanceSecondsPerCall = 0 -- A's window must never close on its own; only the
        // explicit cancellation below ends this run.
        val repositoryA = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0) { routine }
        val workerA = buildWorker(
            routine.id,
            repositoryA,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
            routineWorkOwnershipRepository = sharedOwnership,
        )
        val jobA = launch { workerA.doWork() }
        runCurrent() // A claims, posts its first tick, and suspends in delay(30s)
        assertEquals(1, notifier.shown.size)

        // B -- a replacement starting now (e.g. this routine was just edited elsewhere, which
        // cancels A's unique work and immediately enqueues a fresh one for it). B's claim() is
        // the SECOND ever, so it writes ownership := B and then suspends -- "B claims
        // ownership, pause B before foreground content is published."
        val repositoryB = ScriptedRoutineRepository(clock, advanceSecondsPerCall = 0) { routine }
        val workerB = buildWorker(
            routine.id,
            repositoryB,
            GetLiveDeparturesUseCase(departures, clock),
            notifier,
            scheduler,
            clock,
            widgetUpdater = widgetUpdater,
            routineWorkOwnershipRepository = sharedOwnership,
        )
        val jobB = launch { workerB.doWork() }
        runCurrent() // B claims (ownership now names B) and suspends on the gate, before setForeground

        // A finishes/cancels now -- exactly the moment the real race window used to be
        // exploitable: ownership already names B, but B has not yet posted anything of its own.
        jobA.cancel()
        jobA.join()

        // A's own `finally` must NOT have cleared the shared content: it is no longer the
        // recorded owner, even though B itself is still paused and has posted nothing yet.
        assertEquals("A must not have removed the shared notification", 0, notifier.removeCallCount)
        assertEquals("A must not have cleared the shared widget content", 0, widgetUpdater.clearCallCount)
        assertEquals("A's own earlier post must still be the only thing shown so far", 1, notifier.shown.size)

        // B continues -- its claim() call finally returns, and it goes on to post its own content.
        gate.complete(Unit)
        runCurrent()

        assertEquals("B's content must have been posted once released", 2, notifier.shown.size)

        jobB.cancel()
        jobB.join()
    }

    // ---- Exact-destination journeys: the worker re-filters the ranked list AGAIN, immediately
    // after GetRankedJourneysUseCase returns, and uses that SAME already-filtered list and
    // instant for both the notification projection and routineWidgetUpdater.updateWithJourneys
    // -- see RoutineActiveWindowWorker.doWork's own comment at rawJourneyPlans/journeyPlans. ----

    private fun exactDestinationRoutine(
        id: String = "r1",
        startTime: LocalTime = LocalTime.of(7, 0),
        endTime: LocalTime = LocalTime.of(7, 2),
    ) = CommuteRoutine(
        id = id,
        name = "Airport commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.UNKNOWN,
        lineId = null,
        lineDesignation = null,
        directionCode = null,
        destinationLabel = null,
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = startTime,
        endTime = endTime,
        type = RoutineType.EXACT_DESTINATION,
        journeyOriginId = "origin-id",
        journeyOriginName = "Fruängen",
        journeyDestinationId = "destination-id",
        journeyDestinationName = "Arlanda",
    )

    private fun exactJourney(id: String, departure: Instant, lineDesignation: String = "14") = run {
        val leg = JourneyLeg(
            TransportMode.METRO, lineDesignation, "Direction", "Fruängen", "Arlanda",
            departure, departure.plusSeconds(600), true, emptyList(),
        )
        JourneyPlan(id, "Fruängen", "Arlanda", departure, departure.plusSeconds(600), 0, leg, listOf(leg), emptyList())
    }

    /** A [JourneyRepository] that always returns the same [journeys] -- the "network round-trip"
     * itself does no clock work here; the race this section tests is instead modeled by giving
     * [GetRankedJourneysUseCase] its OWN, separately-controlled [Clock] (see each test's own
     * comment) rather than by choreographing advances within a single shared clock, since
     * production code has no suspension point between GetRankedJourneysUseCase's own post-response
     * `now` read and the worker's subsequent one for a shared TickingClock to advance across. */
    private class FakeJourneyRepository(private val journeys: List<JourneyPlan>) : JourneyRepository {
        /** Captures whatever `searchUntil` the worker's own call actually supplied — most tests
         * in this section don't care, but see "passes windowEnd as searchUntil" below. */
        var receivedSearchUntil: Instant? = null
            private set

        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
            searchUntil: Instant?,
        ): List<JourneyPlan> {
            receivedSearchUntil = searchUntil
            return journeys
        }
    }

    @Test
    fun `the worker removes a journey that expires between use-case filtering and worker processing, agreeing across notification and widget`() =
        runTest {
            val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
            val exactRoutine = exactDestinationRoutine()
            val repository = ScriptedRoutineRepository(clock) { exactRoutine }
            // Two getById calls happen before the journeys fetch (doWork's own initial read, then
            // the loop's own `current` read), each advancing `clock` by 30s -- so `clock` reads
            // 07:01:00 local (05:01:00Z) by the time getRankedJourneys is invoked and by the time
            // this worker's own exactProjectionNow is captured immediately after it returns.
            //
            // GetRankedJourneysUseCase is wired to a DIFFERENT, EARLIER, fixed Clock -- modeling a
            // use case whose own post-response `now` was genuinely earlier than what's true by the
            // time the worker actually gets around to processing its result (the real-world
            // equivalent of network/processing latency between the two). The journey departs at
            // 05:00:58Z: still current per the use case's own 05:00:55Z `now` (58 >= 55), but
            // already departed per the worker's own, later 05:01:00Z (58 < 60).
            val useCaseClock = Clock.fixed(Instant.parse("2026-07-27T05:00:55Z"), zone)
            val borderlineJourney = exactJourney("borderline", Instant.parse("2026-07-27T05:00:58Z"))
            val getRankedJourneys = GetRankedJourneysUseCase(FakeJourneyRepository(listOf(borderlineJourney)), useCaseClock)
            val notifier = RecordingNotifier()
            val widgetUpdater = RecordingWidgetUpdater()

            val worker = buildWorker(
                exactRoutine.id,
                repository,
                GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused for an exact-destination routine") }, clock),
                notifier,
                RecordingScheduler(),
                clock,
                widgetUpdater = widgetUpdater,
                getRankedJourneys = getRankedJourneys,
            )
            worker.doWork()

            // Notification: the expired journey is never shown as live -- NoUpcomingDepartures,
            // not a "0 min" departure. NoUpcomingDepartures, not Unavailable: the search itself
            // genuinely succeeded (FakeJourneyRepository returned a real journey) -- it simply
            // expired by the time the worker re-checked it, which is not a fetch failure and must
            // never be represented as one (see RoutineActiveWindowWorker's own comment on
            // rawJourneyPlans for the production incident this distinction now prevents).
            assertTrue("expected at least the foreground placeholder notification", notifier.shown.isNotEmpty())
            assertTrue(
                "expected no live journey in the notification once it expired",
                notifier.shown.any { it.content is RoutineNotificationContent.NoUpcomingDepartures },
            )
            // Widget: received the WORKER's own re-filtered (empty) list -- never the raw,
            // already-stale one GetRankedJourneysUseCase itself returned -- and fetchFailed =
            // false for the exact same reason the notification isn't Unavailable above: the
            // search itself did not fail.
            assertTrue("expected at least one updateWithJourneys call", widgetUpdater.journeysUpdateCalls.isNotEmpty())
            assertTrue(
                "expected every updateWithJourneys call to have received an empty, already-filtered list",
                widgetUpdater.journeysUpdateCalls.all { (journeys, _) -> journeys.isEmpty() },
            )
            assertTrue(
                "expected every updateWithJourneys call to report fetchFailed = false",
                widgetUpdater.journeysFetchFailedAtUpdate.all { fetchFailed -> !fetchFailed },
            )
        }

    @Test
    fun `the worker passes the identical filtered journey list and instant to both the notification projection and the widget`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val exactRoutine = exactDestinationRoutine()
        val repository = ScriptedRoutineRepository(clock) { exactRoutine }
        // Comfortably upcoming per BOTH clocks -- this proves consistency for a journey that
        // survives, complementing the expiry test above.
        val useCaseClock = Clock.fixed(Instant.parse("2026-07-27T05:00:30Z"), zone)
        val upcomingJourney = exactJourney("upcoming", Instant.parse("2026-07-27T05:05:00Z"))
        val getRankedJourneys = GetRankedJourneysUseCase(FakeJourneyRepository(listOf(upcomingJourney)), useCaseClock)
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            exactRoutine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused for an exact-destination routine") }, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
            getRankedJourneys = getRankedJourneys,
        )
        worker.doWork()

        val liveContent = notifier.shown.map { it.content }.filterIsInstance<RoutineNotificationContent.Live>()
        assertTrue("expected the notification to show the surviving journey live", liveContent.isNotEmpty())
        val notificationDeparture = liveContent.first().departures.single()
        assertEquals("14", notificationDeparture.lineDesignation)

        assertTrue("expected at least one updateWithJourneys call", widgetUpdater.journeysUpdateCalls.isNotEmpty())
        val (journeysAtUpdate, nowAtUpdate) = widgetUpdater.journeysUpdateCalls.first()
        assertEquals(listOf("upcoming"), journeysAtUpdate.map { it.journeyId })
        // Same instant the notification projection itself used for this tick: recomputing the
        // notification's own countdown against nowAtUpdate reproduces exactly what it showed --
        // proving both surfaces shared one eligibility decision AND one timestamp, not two
        // independently-read ones that merely happened not to disagree this time.
        assertEquals(notificationDeparture.minutesRemaining, countdownMinutes(nowAtUpdate, upcomingJourney.departureTime))
    }

    @Test
    fun `the worker's notification exposes two departures when two journeys survive filtering, not just the first`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        val exactRoutine = exactDestinationRoutine()
        val repository = ScriptedRoutineRepository(clock) { exactRoutine }
        val useCaseClock = Clock.fixed(Instant.parse("2026-07-27T05:00:30Z"), zone)
        // The backend already places these in PRIMARY -> NEXT chronological order (see
        // backend/src/routes/journeys.ts's own doc) -- the worker's own notification projection
        // must expose both, not only the first, now that the notification infrastructure's
        // second departure row is actually used for an exact-destination routine.
        val primary = exactJourney("primary", Instant.parse("2026-07-27T05:05:00Z"), lineDesignation = "14")
        val next = exactJourney("next", Instant.parse("2026-07-27T05:20:00Z"), lineDesignation = "17")
        val getRankedJourneys = GetRankedJourneysUseCase(FakeJourneyRepository(listOf(primary, next)), useCaseClock)
        val notifier = RecordingNotifier()
        val widgetUpdater = RecordingWidgetUpdater()

        val worker = buildWorker(
            exactRoutine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused for an exact-destination routine") }, clock),
            notifier,
            RecordingScheduler(),
            clock,
            widgetUpdater = widgetUpdater,
            getRankedJourneys = getRankedJourneys,
        )
        worker.doWork()

        val liveContent = notifier.shown.map { it.content }.filterIsInstance<RoutineNotificationContent.Live>()
        assertTrue("expected the notification to show a live result", liveContent.isNotEmpty())
        val departures = liveContent.first().departures
        assertEquals(2, departures.size)
        assertEquals("14", departures[0].lineDesignation)
        assertEquals("17", departures[1].lineDesignation)
    }

    @Test
    fun `the worker passes this occurrence's own windowEnd as searchUntil, never an invented horizon`() = runTest {
        val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
        // endTime = 07:02 local -- exactDestinationRoutine()'s own default.
        val exactRoutine = exactDestinationRoutine()
        val repository = ScriptedRoutineRepository(clock) { exactRoutine }
        val journeyRepository = FakeJourneyRepository(listOf(exactJourney("primary", Instant.parse("2026-07-27T05:01:00Z"))))
        val getRankedJourneys = GetRankedJourneysUseCase(journeyRepository, Clock.fixed(clock.instant(), zone))

        val worker = buildWorker(
            exactRoutine.id,
            repository,
            GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused for an exact-destination routine") }, clock),
            RecordingNotifier(),
            RecordingScheduler(),
            clock,
            widgetUpdater = RecordingWidgetUpdater(),
            getRankedJourneys = getRankedJourneys,
        )
        worker.doWork()

        // 07:02 local (this occurrence's own configured endTime) = 05:02:00Z, the same
        // Europe/Stockholm summer offset the "07:00 local" clock start above already uses.
        assertEquals(Instant.parse("2026-07-27T05:02:00Z"), journeyRepository.receivedSearchUntil)
    }

    /** Always throws -- models a genuine search failure (network/backend error), as opposed to
     * [FakeJourneyRepository] succeeding with a list that later turns out empty or expired. */
    private class ThrowingJourneyRepository : JourneyRepository {
        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
            searchUntil: Instant?,
        ): List<JourneyPlan> = throw IOException("SL Journey Planner unavailable")
    }

    @Test
    fun `a genuine search failure is reported as Unavailable, not NoUpcomingDepartures, and the widget receives fetchFailed = true`() =
        runTest {
            val clock = TickingClock(Instant.parse("2026-07-27T05:00:00Z"), zone) // 07:00 local, Monday
            val exactRoutine = exactDestinationRoutine()
            val repository = ScriptedRoutineRepository(clock) { exactRoutine }
            val getRankedJourneys = GetRankedJourneysUseCase(ThrowingJourneyRepository(), Clock.fixed(clock.instant(), zone))
            val notifier = RecordingNotifier()
            val widgetUpdater = RecordingWidgetUpdater()

            val worker = buildWorker(
                exactRoutine.id,
                repository,
                GetLiveDeparturesUseCase(FakeDepartureRepository { error("unused for an exact-destination routine") }, clock),
                notifier,
                RecordingScheduler(),
                clock,
                widgetUpdater = widgetUpdater,
                getRankedJourneys = getRankedJourneys,
            )
            worker.doWork()

            // Unlike an empty or all-expired result (see the two tests above), a search that
            // never completed at all must still read as a genuine failure -- the one case
            // Unavailable's "Couldn't load departures right now. Will try again soon." copy is
            // actually correct for.
            assertTrue("expected at least the foreground placeholder notification", notifier.shown.isNotEmpty())
            assertTrue(
                "expected the notification to report Unavailable for a genuine search failure",
                notifier.shown.any { it.content is RoutineNotificationContent.Unavailable },
            )
            assertTrue("expected at least one updateWithJourneys call", widgetUpdater.journeysUpdateCalls.isNotEmpty())
            assertTrue(
                "expected every updateWithJourneys call to report fetchFailed = true",
                widgetUpdater.journeysFetchFailedAtUpdate.isNotEmpty() && widgetUpdater.journeysFetchFailedAtUpdate.all { it },
            )
        }
}
