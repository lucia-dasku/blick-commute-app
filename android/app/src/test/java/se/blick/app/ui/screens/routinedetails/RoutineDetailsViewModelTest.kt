package se.blick.app.ui.screens.routinedetails

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.blick.app.data.local.datastore.AppSettings
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.data.repository.DisruptionRepository
import se.blick.app.data.repository.JourneyRepository
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
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
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.GetDisruptionsUseCase
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.GetRankedJourneysUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.notification.RoutineNotificationModel
import se.blick.app.notification.RoutineNotifier
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.NotificationRecoveryReporter
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.ui.navigation.Routes
import se.blick.app.widget.RoutineWidgetUpdater
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val now: Instant = Instant.parse("2026-07-28T08:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    // Matches the clock's own zone so every existing `LocalDate.now(clock)`-based
    // expectation in this file still holds -- see the dedicated device-zone test below for
    // proof that a non-UTC zone is actually honored, distinct from this default.
    private val deviceZoneProvider = DeviceZoneProvider { ZoneOffset.UTC }

    private val stopArea = StopAreaRef(id = 9145, name = "Fruängen", type = "BUSTERM")
    private val stopPoint = StopPointRef(id = 1, name = "Fruängen", designation = "A")

    private fun sampleRoutine(
        id: String = "r1",
        pausedDate: LocalDate? = null,
        enabled: Boolean = true,
        siteId: Long = 9145,
    ) = CommuteRoutine(
        id = id,
        name = "Morning commute",
        siteId = siteId,
        siteName = "Fruängen",
        transportMode = TransportMode.BUS,
        lineId = null,
        lineDesignation = "705",
        directionCode = null,
        destinationLabel = "Segeltorp",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        enabled = enabled,
        pausedDate = pausedDate,
    )

    private fun upcomingDeparture(id: String = UUID.randomUUID().toString()) = Departure(
        departureId = id,
        line = LineRef(id = 705, designation = "705", transportMode = TransportMode.BUS),
        direction = "Southbound",
        directionCode = 1,
        destination = "Segeltorp",
        via = null,
        stopArea = stopArea,
        stopPoint = stopPoint,
        scheduledTime = now.plusSeconds(300),
        expectedTime = null,
        state = "EXPECTED",
        isCancelled = false,
        journey = Journey(id = 1, state = "EXPECTED", predictionState = null),
        tripDeviations = emptyList(),
    )

    private fun resultOf(vararg departures: Departure) = DeparturesResult(
        fetchedAt = now,
        siteId = 9145,
        departures = departures.toList(),
    )

    // ---- RoutineRepository fakes ----

    /** Mutable so action tests (enable/disable, pause/resume, delete, reload) can observe the
     * effect of a write through a later [getById] call, not just via the ViewModel's own
     * locally-merged copy. */
    private class FakeRoutineRepository(initial: CommuteRoutine?) : RoutineRepository {
        var lastRequestedId: String? = null
        private val state = MutableStateFlow(initial)
        val deletedIds = mutableListOf<String>()
        val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
        val pauseForDateCalls = mutableListOf<Pair<String, LocalDate>>()
        val clearPauseCalls = mutableListOf<String>()

        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(listOfNotNull(state.value))
        override suspend fun getById(id: String): CommuteRoutine? {
            lastRequestedId = id
            return state.value?.takeIf { it.id == id }
        }
        override suspend fun save(routine: CommuteRoutine) {
            state.value = routine
        }
        override suspend fun delete(id: String) {
            deletedIds += id
            if (state.value?.id == id) state.value = null
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            pauseForDateCalls += id to date
            state.update { current -> if (current?.id == id) current.copy(pausedDate = date) else current }
        }
        override suspend fun clearPause(id: String) {
            clearPauseCalls += id
            state.update { current -> if (current?.id == id) current.copy(pausedDate = null) else current }
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            setEnabledCalls += id to enabled
            state.update { current -> if (current?.id == id) current.copy(enabled = enabled) else current }
        }
        override suspend fun hasAnyRoutine(): Boolean = state.value != null
    }

    /** Always succeeds on [getById]/[save], but each of the four action writes can be
     * independently forced to fail — for proving a write failure leaves the previously
     * stored state untouched and surfaces a friendly, retryable failure flag instead of a
     * raw exception. */
    private class FailingActionRoutineRepository(
        initial: CommuteRoutine,
        private val failSetEnabled: Boolean = false,
        private val failPauseForDate: Boolean = false,
        private val failClearPause: Boolean = false,
        private val failDelete: Boolean = false,
    ) : RoutineRepository {
        private val state = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<CommuteRoutine>> = MutableStateFlow(listOf(state.value))
        override suspend fun getById(id: String): CommuteRoutine? = state.value.takeIf { it.id == id }
        override suspend fun save(routine: CommuteRoutine) {
            state.value = routine
        }
        override suspend fun delete(id: String) {
            if (failDelete) throw RuntimeException("boom")
        }
        override suspend fun pauseForDate(id: String, date: LocalDate) {
            if (failPauseForDate) throw RuntimeException("boom")
            state.value = state.value.copy(pausedDate = date)
        }
        override suspend fun clearPause(id: String) {
            if (failClearPause) throw RuntimeException("boom")
            state.value = state.value.copy(pausedDate = null)
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            if (failSetEnabled) throw RuntimeException("boom")
            state.value = state.value.copy(enabled = enabled)
        }
        override suspend fun hasAnyRoutine(): Boolean = true
    }

    // ---- DepartureRepository fakes ----

    private class FakeDepartureRepository(private val result: DeparturesResult) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            callCount++
            return result
        }
    }

    private class FailingDepartureRepository(private val error: Throwable) : DepartureRepository {
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult = throw error
    }

    /** Succeeds with [result] while [shouldFail] is false; throws [failure] once flipped on
     * — for proving a later refresh failure falls back to Stale using the earlier success. */
    private class ToggleableDepartureRepository(
        private val result: DeparturesResult,
        var shouldFail: Boolean = false,
        private val failure: Throwable = IOException("network down"),
    ) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            callCount++
            if (shouldFail) throw failure
            return result
        }
    }

    /** Succeeds with a per-site configured result for sites in [resultsBySite]; throws
     * [failure] for any site listed in [failingSites] — for proving a fetch against a NEW
     * site (e.g. after editing a routine to a different station) can genuinely fail
     * independently of whatever succeeded for the OLD site. */
    private class PerSiteDepartureRepository(
        private val resultsBySite: Map<Long, DeparturesResult> = emptyMap(),
        private val failingSites: Set<Long> = emptySet(),
        private val failure: Throwable = IOException("network down"),
    ) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            callCount++
            if (siteId in failingSites) throw failure
            return resultsBySite[siteId] ?: error("PerSiteDepartureRepository: no result configured for site $siteId")
        }
    }

    /** Each call suspends on its own [CompletableDeferred], indexed by call order — lets a
     * test resolve fetches out of order to reproduce a slow/superseded refresh. Same pattern
     * as RoutineCreateViewModelTest's ControllableDirectionOptionsSource. */
    private class ControllableDepartureRepository : DepartureRepository {
        private val pending = mutableListOf<CompletableDeferred<DeparturesResult>>()
        val callCount: Int get() = pending.size
        override suspend fun getDepartures(siteId: Long, forecastMinutes: Int?): DeparturesResult {
            val deferred = CompletableDeferred<DeparturesResult>()
            pending += deferred
            return deferred.await()
        }
        fun complete(callIndex: Int, result: DeparturesResult) {
            pending[callIndex].complete(result)
        }
    }

    // ---- DisruptionRepository fakes ----

    private fun sampleDisruption(id: String = "d1") = Disruption(
        disruptionId = id,
        version = 1,
        createdAt = now,
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage("Header $id", "Details $id", null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    private class FakeDisruptionRepository(private val result: List<Disruption> = emptyList()) : DisruptionRepository {
        var callCount = 0
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            callCount++
            return result
        }
    }

    private class FailingDisruptionRepository(private val error: Throwable) : DisruptionRepository {
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> = throw error
    }

    /** Each call suspends on its own [CompletableDeferred] -- same pattern as
     * [ControllableDepartureRepository], for proving disruptions loading is genuinely
     * independent of (never blocked by, never blocking) departures loading. */
    private class ControllableDisruptionRepository : DisruptionRepository {
        private val pending = mutableListOf<CompletableDeferred<List<Disruption>>>()
        val callCount: Int get() = pending.size
        override suspend fun getDisruptions(siteId: Long, lineId: Long?, transportMode: TransportMode?): List<Disruption> {
            val deferred = CompletableDeferred<List<Disruption>>()
            pending += deferred
            return deferred.await()
        }
        fun complete(callIndex: Int, result: List<Disruption>) {
            pending[callIndex].complete(result)
        }
    }

    /** Records every [RoutineNotifier] call so the debug-trigger tests can assert on them
     * without needing a real Android `NotificationManager` — the concrete Android
     * implementation is covered separately by `RoutineNotificationBuilderTest`. Returns
     * [resultToReturn] (default [NotificationPostResult.Posted]) from every [showOrUpdate]
     * call, so tests can also simulate [NotificationPostResult.NotificationsDisabled]/
     * [NotificationPostResult.Failed] and assert the ViewModel propagates them unchanged. */
    private class FakeRoutineNotifier(
        private val resultToReturn: NotificationPostResult = NotificationPostResult.Posted,
    ) : RoutineNotifier {
        val shown = mutableListOf<RoutineNotificationModel>()
        var removeCallCount = 0
        override fun showOrUpdate(model: RoutineNotificationModel): NotificationPostResult {
            shown += model
            return resultToReturn
        }
        override fun remove() {
            removeCallCount++
        }
    }

    /** Records every scheduling call so tests can assert save/edit/enable/disable/pause/
     * resume/delete each correctly replace or cancel this routine's scheduled work, without
     * needing a real WorkManager (that integration is covered separately by
     * `WorkManagerRoutineSchedulerTest`). */
    private class FakeRoutineScheduler : RoutineScheduler {
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

    /** Always throws on [scheduleActivation]/[cancelActivation] — for proving a scheduling or
     * cleanup failure AFTER a successful Room write must never be reported as that action
     * having failed (persistence and scheduling are two different results — see
     * toggleEnabled/pauseToday/resumeToday/deleteRoutine's own class docs). [throwCancellation]
     * simulates a genuine coroutine cancellation instead of an ordinary failure, to prove it
     * still propagates unconverted through the now-separate scheduling try/catch. */
    private class FailingRoutineScheduler(
        private val throwCancellation: Boolean = false,
    ) : RoutineScheduler {
        var scheduleCallCount = 0
        var cancelCallCount = 0
        private fun failure(): Throwable = if (throwCancellation) CancellationException("test cancellation") else RuntimeException("boom")
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduleCallCount++
            throw failure()
        }
        override fun cancelActivation(routineId: String) {
            cancelCallCount++
            throw failure()
        }
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    /** Fails while [shouldFail] is true, succeeds once flipped off — for proving
     * [RoutineDetailsViewModel.retryScheduling] succeeds once the underlying failure clears. */
    private class ToggleableRoutineScheduler(var shouldFail: Boolean) : RoutineScheduler {
        val scheduledRoutines = mutableListOf<CommuteRoutine>()
        var scheduleCallCount = 0
        override fun scheduleActivation(routine: CommuteRoutine) {
            scheduleCallCount++
            if (shouldFail) throw RuntimeException("boom")
            scheduledRoutines += routine
        }
        override fun cancelActivation(routineId: String) = Unit
        override suspend fun isActivationRunning(routineId: String): Boolean = false
    }

    /** Records every call — for proving toggleEnabled/pauseToday/resumeToday/deleteRoutine/
     * reload each reconcile the widget. See the identical fake in `RoutineListViewModelTest`/
     * `RoutineCreateViewModelTest` for why each ViewModel test file keeps its own copy. */
    private class FakeRoutineWidgetUpdater : RoutineWidgetUpdater {
        var reconcileCallCount = 0
        var clearCallCount = 0
        val updateCalls = mutableListOf<CommuteRoutine>()

        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            updateCalls += routine
        }

        override suspend fun clear() {
            clearCallCount++
        }

        override suspend fun reconcile() {
            reconcileCallCount++
        }

        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
    }

    /** Throws from every method — proves each call site wraps its [RoutineWidgetUpdater] call
     * with `runWidgetUpdateSafely` rather than letting a widget/Glance/DataStore failure escape
     * into this ViewModel's own success/failure state, or crash `viewModelScope` (which has no
     * default exception handler on Android). */
    private class FailingRoutineWidgetUpdater : RoutineWidgetUpdater {
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {
            throw RuntimeException("widget update failed")
        }
        override suspend fun clear() {
            throw RuntimeException("widget update failed")
        }
        override suspend fun reconcile() {
            throw RuntimeException("widget update failed")
        }
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) {
            throw RuntimeException("widget update failed")
        }
    }

    /** Minimal in-memory [AppSettingsDataStore] fake — see the identical fake in
     * `RoutineCreateViewModelTest` for why each ViewModel test file keeps its own copy rather
     * than sharing one across packages. */
    private class FakeAppSettingsDataStore(initial: AppSettings = AppSettings()) : AppSettingsDataStore {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setUseDarkTheme(useDarkTheme: Boolean?) {
            state.value = state.value.copy(useDarkTheme = useDarkTheme)
        }
        override suspend fun setHasSeenNotificationRationale(seen: Boolean) {
            state.value = state.value.copy(hasSeenNotificationRationale = seen)
        }
        override suspend fun setHasAcknowledgedAttribution(acknowledged: Boolean) {
            state.value = state.value.copy(hasAcknowledgedAttribution = acknowledged)
        }
    }

    /** Settable in-memory [NotificationAvailabilityChecker] fake — lets tests flip the
     * "current" availability (e.g. while the screen is stopped, simulating the user changing
     * a system Settings toggle) and then assert [RoutineDetailsViewModel.refreshNotificationAvailability]
     * (driven by [RoutineDetailsViewModel.runAutoRefresh] on every lifecycle resume) actually
     * picks up the new value, without a real Android `NotificationManager`. */
    private class FakeNotificationAvailabilityChecker(
        var current: NotificationAvailability = NotificationAvailability.Available,
    ) : NotificationAvailabilityChecker {
        override fun check(): NotificationAvailability = current
    }

    /** Settable in-memory [PromotedNotificationChecker] fake — see that interface's own doc
     * for why this is a separate concern from [NotificationAvailability] above. */
    private class FakePromotedNotificationChecker(
        var promotable: Boolean = false,
    ) : PromotedNotificationChecker {
        override fun isPromotable(): Boolean = promotable
    }

    /** Records every [reportUnavailable] call — for proving this ViewModel only ever REPORTS
     * an observed unavailable state rather than scheduling/reconciling anything itself; the
     * real recovery/scheduling decision now lives solely in
     * `se.blick.app.scheduling.NotificationRecoveryCoordinator` (see that class's own doc). */
    private class FakeNotificationRecoveryReporter : NotificationRecoveryReporter {
        var reportUnavailableCallCount = 0
        override suspend fun reportUnavailable() {
            reportUnavailableCallCount++
        }
    }

    /** In-memory [StaleSnapshotRepository] fake, backed by a plain mutable map rather than
     * Room — a SHARED instance passed to two separately-constructed ViewModels is exactly how
     * these tests simulate "the process was killed and recreated": a fresh ViewModel instance
     * (no in-memory fields of its own left over) still sees whatever the previous instance
     * persisted, because durability now lives here rather than in a ViewModel-owned field. */
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

    private fun viewModel(
        routine: CommuteRoutine? = sampleRoutine(),
        routineId: String = routine?.id ?: "missing",
        departures: DepartureRepository = FakeDepartureRepository(resultOf(upcomingDeparture())),
        disruptions: DisruptionRepository = FakeDisruptionRepository(),
        routines: RoutineRepository = FakeRoutineRepository(routine),
        staleSnapshots: StaleSnapshotRepository = FakeStaleSnapshotRepository(),
        notifier: RoutineNotifier = FakeRoutineNotifier(),
        scheduler: RoutineScheduler = FakeRoutineScheduler(),
        widgetUpdater: RoutineWidgetUpdater = FakeRoutineWidgetUpdater(),
        appSettingsDataStore: AppSettingsDataStore = FakeAppSettingsDataStore(),
        notificationAvailabilityChecker: NotificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
        promotedNotificationChecker: PromotedNotificationChecker = FakePromotedNotificationChecker(),
        notificationRecoveryReporter: NotificationRecoveryReporter = FakeNotificationRecoveryReporter(),
        deviceZoneProvider: DeviceZoneProvider = this.deviceZoneProvider,
        // Defaults to the file-level fixed clock -- every existing test keeps using that same
        // instant unless it opts into a controllable one (see the exact-journey refresh tests,
        // which need a Clock they can advance BETWEEN consecutive automatic fetches).
        clock: Clock = this.clock,
        getRankedJourneys: GetRankedJourneysUseCase? = null,
    ) = RoutineDetailsViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to routineId)),
        routineRepository = routines,
        getLiveDepartures = GetLiveDeparturesUseCase(departures, clock),
        getDisruptions = GetDisruptionsUseCase(disruptions),
        staleSnapshotRepository = staleSnapshots,
        routineNotifier = notifier,
        routineScheduler = scheduler,
        routineWidgetUpdater = widgetUpdater,
        appSettingsDataStore = appSettingsDataStore,
        notificationAvailabilityChecker = notificationAvailabilityChecker,
        promotedNotificationChecker = promotedNotificationChecker,
        notificationRecoveryReporter = notificationRecoveryReporter,
        clock = clock,
        deviceZoneProvider = deviceZoneProvider,
        getRankedJourneys = getRankedJourneys,
    )

    // ---- Routine loading ----

    @Test
    fun `updating exact journey modes persists refreshes and reschedules the same routine`() = runTest(dispatcher) {
        val routine = sampleRoutine().copy(
            type = RoutineType.EXACT_DESTINATION,
            transportMode = TransportMode.UNKNOWN,
            journeyOriginId = "origin-id",
            journeyOriginName = "Fruängen",
            journeyDestinationId = "destination-id",
            journeyDestinationName = "Mariatorget",
        )
        val repository = FakeRoutineRepository(routine)
        val scheduler = FakeRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.updateJourneyTransportModes(setOf(TransportMode.TRAIN, TransportMode.BUS))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            setOf(TransportMode.TRAIN, TransportMode.BUS),
            repository.getById(routine.id)?.allowedJourneyTransportModes,
        )
        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), vm.uiState.value.routine?.allowedJourneyTransportModes)
        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), scheduler.scheduledRoutines.last().allowedJourneyTransportModes)
        assertFalse(vm.uiState.value.journeyTransportModesUpdateFailed)
    }

    @Test
    fun `the correct routine is loaded using the navigation id`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r42")
        val repository = FakeRoutineRepository(routine)
        val vm = RoutineDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to "r42")),
            routineRepository = repository,
            getLiveDepartures = GetLiveDeparturesUseCase(FakeDepartureRepository(resultOf(upcomingDeparture())), clock),
            getDisruptions = GetDisruptionsUseCase(FakeDisruptionRepository()),
            staleSnapshotRepository = FakeStaleSnapshotRepository(),
            routineNotifier = FakeRoutineNotifier(),
            routineScheduler = FakeRoutineScheduler(),
            routineWidgetUpdater = FakeRoutineWidgetUpdater(),
            appSettingsDataStore = FakeAppSettingsDataStore(),
            notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
            promotedNotificationChecker = FakePromotedNotificationChecker(),
            notificationRecoveryReporter = FakeNotificationRecoveryReporter(),
            clock = clock,
            deviceZoneProvider = deviceZoneProvider,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("r42", repository.lastRequestedId)
        assertEquals(routine, vm.uiState.value.routine)
        assertFalse(vm.uiState.value.isRoutineLoading)
        assertFalse(vm.uiState.value.routineNotFound)
    }

    @Test
    fun `a missing routine is reported without crashing and no departures are fetched`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(routine = null, routineId = "does-not-exist", departures = departures)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.routineNotFound)
        assertFalse(vm.uiState.value.isRoutineLoading)
        assertNull(vm.uiState.value.routine)
        assertEquals(0, departures.callCount)
    }

    @Test
    fun `paused-today status reflects the routine's pausedDate against the injected clock`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val pausedToday = viewModel(routine = sampleRoutine(pausedDate = today))
        val pausedYesterday = viewModel(routine = sampleRoutine(id = "r2", pausedDate = today.minusDays(1)), routineId = "r2")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(pausedToday.uiState.value.isPausedToday)
        assertFalse(pausedYesterday.uiState.value.isPausedToday)
    }

    // ---- Departure loading wired to the engine ----

    @Test
    fun `the departure fetch begins automatically once the routine has loaded`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, departures.callCount)
    }

    @Test
    fun `Loading is shown until the departure fetch resolves, then Live`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)

        dispatcher.scheduler.advanceUntilIdle() // reach the fetch's suspension point
        assertEquals(LiveDeparturesState.Loading, vm.uiState.value.departures)

        departures.complete(0, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.departures is LiveDeparturesState.Live)
    }

    @Test
    fun `a Live state exposes the prepared departures returned by the engine`() = runTest(dispatcher) {
        val a = upcomingDeparture("dep-a")
        val b = upcomingDeparture("dep-b").copy(scheduledTime = now.plusSeconds(600))
        val vm = viewModel(departures = FakeDepartureRepository(resultOf(a, b)))
        dispatcher.scheduler.advanceUntilIdle()

        val live = vm.uiState.value.departures as LiveDeparturesState.Live
        assertEquals(listOf("dep-a", "dep-b"), live.snapshot.departures.map { it.departureId })
    }

    @Test
    fun `no matching departures produces NoUpcomingDepartures`() = runTest(dispatcher) {
        val vm = viewModel(departures = FakeDepartureRepository(resultOf()))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.departures is LiveDeparturesState.NoUpcomingDepartures)
    }

    @Test
    fun `a connectivity failure with no previous data produces Offline`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(IOException("unable to resolve host")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDeparturesState.Offline, vm.uiState.value.departures)
    }

    @Test
    fun `a non-connectivity failure with no previous data produces Unavailable`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(RuntimeException("boom")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDeparturesState.Unavailable, vm.uiState.value.departures)
    }

    @Test
    fun `a cancellation while fetching departures does not surface as an error state`() = runTest(dispatcher) {
        val vm = viewModel(departures = FailingDepartureRepository(CancellationException("test cancellation")))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value.departures
        assertEquals(LiveDeparturesState.Loading, state)
    }

    // ---- Snapshot retention + Stale fallback ----

    @Test
    fun `a refresh failure after a successful fetch shows Stale and preserves the previous departures`() = runTest(dispatcher) {
        val departures = ToggleableDepartureRepository(resultOf(upcomingDeparture("kept")))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        val liveSnapshot = (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot

        departures.shouldFail = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val stale = vm.uiState.value.departures
        assertTrue(stale is LiveDeparturesState.Stale)
        assertEquals(liveSnapshot, (stale as LiveDeparturesState.Stale).snapshot)
        assertEquals(listOf("kept"), stale.snapshot.departures.map { it.departureId })
    }

    @Test
    fun `a persisted snapshot survives a fresh ViewModel instance, simulating process death`() = runTest(dispatcher) {
        // A brand-new RoutineDetailsViewModel instance -- no in-memory fields carried over from
        // the first one -- backed by the SAME StaleSnapshotRepository, is exactly what "the
        // process was killed and recreated between these two instances" looks like from this
        // ViewModel's perspective (see FakeStaleSnapshotRepository's own doc).
        val routine = sampleRoutine()
        val routines = FakeRoutineRepository(routine)
        val staleSnapshots = FakeStaleSnapshotRepository()

        val firstVm = viewModel(
            routine = routine,
            routines = routines,
            staleSnapshots = staleSnapshots,
            departures = FakeDepartureRepository(resultOf(upcomingDeparture("kept"))),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(firstVm.uiState.value.departures is LiveDeparturesState.Live)

        val secondVm = viewModel(
            routine = routine,
            routines = routines,
            staleSnapshots = staleSnapshots,
            departures = FailingDepartureRepository(IOException("network down")),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val state = secondVm.uiState.value.departures
        assertTrue("expected Stale using the first instance's persisted snapshot, got $state", state is LiveDeparturesState.Stale)
        assertEquals(listOf("kept"), (state as LiveDeparturesState.Stale).snapshot.departures.map { it.departureId })
    }

    // ---- Manual refresh ----

    @Test
    fun `refresh triggers a new departures fetch`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, departures.callCount)
    }

    @Test
    fun `existing departures remain visible while a manual refresh is in progress`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        departures.complete(0, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()
        val liveBeforeRefresh = vm.uiState.value.departures
        assertTrue(liveBeforeRefresh is LiveDeparturesState.Live)
        assertFalse(vm.uiState.value.isRefreshingDepartures)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle() // let the refresh reach its suspension point

        assertTrue(vm.uiState.value.isRefreshingDepartures)
        assertEquals(liveBeforeRefresh, vm.uiState.value.departures)

        departures.complete(1, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isRefreshingDepartures)
    }

    @Test
    fun `an older in-flight refresh cannot overwrite a newer refresh's result`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        departures.complete(0, resultOf(upcomingDeparture("initial")))
        dispatcher.scheduler.advanceUntilIdle()

        vm.refresh() // call index 1 (older refresh), left pending
        dispatcher.scheduler.advanceUntilIdle()
        vm.refresh() // call index 2 (newer refresh) -- must supersede index 1
        dispatcher.scheduler.advanceUntilIdle()

        departures.complete(2, resultOf(upcomingDeparture("newer")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("newer"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )

        // The older, superseded refresh finally resolves late -- must be ignored.
        departures.complete(1, resultOf(upcomingDeparture("stale-older")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("newer"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )
        assertFalse(vm.uiState.value.isRefreshingDepartures)
    }

    // ---- Enable / disable ----

    @Test
    fun `an enabled routine can be disabled`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertEquals(listOf(routine.id to false), repository.setEnabledCalls)
        assertFalse(vm.uiState.value.isTogglingEnabled)
    }

    @Test
    fun `a disabled routine can be enabled`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = false)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.routine?.enabled)
        assertEquals(listOf(routine.id to true), repository.setEnabledCalls)
    }

    @Test
    fun `toggling enabled never touches pausedDate`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertTrue(repository.pauseForDateCalls.isEmpty())
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a failed enable-disable write leaves the stored state untouched with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FailingActionRoutineRepository(routine, failSetEnabled = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.routine?.enabled)
        assertTrue(vm.uiState.value.enabledActionFailed)
        assertFalse(vm.uiState.value.isTogglingEnabled)
    }

    @Test
    fun `repeated enable-disable taps do not overlap`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.setEnabledCalls.size)
    }

    // ---- Pause / resume today ----

    @Test
    fun `pause today stores today's date from the injected clock`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        val today = LocalDate.now(clock)
        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id to today), repository.pauseForDateCalls)
    }

    @Test
    fun `pause today resolves today's date in the device's own zone, not the clock's UTC instant`() = runTest(dispatcher) {
        // 2026-07-31T22:30:00Z is already 2026-08-01 in Stockholm's summer UTC+2 offset --
        // proves pauseToday agrees with RoutineActiveWindowWorker's own zonedNow() (and
        // StopRoutineNotificationAction's identical computation) instead of silently pausing
        // the wrong calendar day shortly after local midnight, as a zone-less
        // LocalDate.now(clock) would have.
        val lateNightClock = Clock.fixed(Instant.parse("2026-07-31T22:30:00Z"), ZoneOffset.UTC)
        val stockholmZone = DeviceZoneProvider { ZoneId.of("Europe/Stockholm") }
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = RoutineDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to routine.id)),
            routineRepository = repository,
            getLiveDepartures = GetLiveDeparturesUseCase(FakeDepartureRepository(resultOf(upcomingDeparture())), lateNightClock),
            getDisruptions = GetDisruptionsUseCase(FakeDisruptionRepository()),
            staleSnapshotRepository = FakeStaleSnapshotRepository(),
            routineNotifier = FakeRoutineNotifier(),
            routineScheduler = FakeRoutineScheduler(),
            routineWidgetUpdater = FakeRoutineWidgetUpdater(),
            appSettingsDataStore = FakeAppSettingsDataStore(),
            notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
            promotedNotificationChecker = FakePromotedNotificationChecker(),
            notificationRecoveryReporter = FakeNotificationRecoveryReporter(),
            clock = lateNightClock,
            deviceZoneProvider = stockholmZone,
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        val expected = LocalDate.of(2026, 8, 1)
        assertEquals(expected, vm.uiState.value.routine?.pausedDate)
        assertEquals(listOf(routine.id to expected), repository.pauseForDateCalls)
    }

    @Test
    fun `resume today clears the pausedDate`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isPausedToday)

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id), repository.clearPauseCalls)
    }

    @Test
    fun `pausing today never touches the enabled flag`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = false)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertTrue(repository.setEnabledCalls.isEmpty())
    }

    @Test
    fun `a failed pause write leaves the stored state untouched with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FailingActionRoutineRepository(routine, failPauseForDate = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertTrue(vm.uiState.value.pauseActionFailed)
        assertFalse(vm.uiState.value.isTogglingPause)
    }

    @Test
    fun `repeated pause-resume taps do not overlap`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.pauseForDateCalls.size)
    }

    // ---- Expired pause auto-cleanup ----

    @Test
    fun `an expired pausedDate is cleared automatically on load`() = runTest(dispatcher) {
        val yesterday = LocalDate.now(clock).minusDays(1)
        val routine = sampleRoutine(pausedDate = yesterday)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertEquals(listOf(routine.id), repository.clearPauseCalls)
    }

    @Test
    fun `today's pause is not treated as expired`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertTrue(vm.uiState.value.isPausedToday)
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a null pausedDate causes no cleanup write`() = runTest(dispatcher) {
        val routine = sampleRoutine(pausedDate = null)
        val repository = FakeRoutineRepository(routine)
        viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    @Test
    fun `a future pausedDate is never treated as expired`() = runTest(dispatcher) {
        val tomorrow = LocalDate.now(clock).plusDays(1)
        val routine = sampleRoutine(pausedDate = tomorrow)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(tomorrow, vm.uiState.value.routine?.pausedDate)
        assertFalse(vm.uiState.value.isPausedToday)
        assertTrue(repository.clearPauseCalls.isEmpty())
    }

    // ---- Deletion ----

    @Test
    fun `confirming delete removes only that routine`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r9")
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routineId = "r9", routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("r9"), repository.deletedIds)
    }

    @Test
    fun `a successful delete invokes onDeleted so the caller can navigate to the list`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(deleted)
        assertFalse(vm.uiState.value.isDeleting)
    }

    @Test
    fun `a delete failure keeps the screen on the routine with a friendly retryable failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FailingActionRoutineRepository(routine, failDelete = true)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(deleted)
        assertTrue(vm.uiState.value.deleteFailed)
        assertFalse(vm.uiState.value.isDeleting)
        assertEquals(routine, vm.uiState.value.routine)
    }

    @Test
    fun `repeated confirm taps do not delete twice`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.deletedIds.size)
    }

    // ---- reload() after a successful edit (see BlickNavHost's savedStateHandle signal) ----

    @Test
    fun `reload fetches a new departure result once the routine's identity changes`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture("before")))
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        // Simulates what a successful edit does: routineRepository.save() upserts the same
        // id with a new site.
        repository.save(routine.copy(siteId = 9192, siteName = "Slussen"))

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Slussen", vm.uiState.value.routine?.siteName)
        assertEquals(2, departures.callCount)
    }

    @Test
    fun `reload does not re-fetch departures when the departure-relevant fields are unchanged`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        // A name-only edit -- same site/line/direction/mode, so no new fetch is warranted.
        repository.save(routine.copy(name = "Renamed"))

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Renamed", vm.uiState.value.routine?.name)
        assertEquals(1, departures.callCount)
    }

    @Test
    fun `a stale in-flight fetch for the old configuration cannot overwrite reload's new one`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val departures = ControllableDepartureRepository()
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle() // reach the initial fetch's suspension point (call index 0)

        repository.save(routine.copy(siteId = 9192, siteName = "Slussen"))
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle() // reach reload's fetch's suspension point too (call index 1)

        // The OLD (pre-edit configuration) fetch finally resolves late -- must be ignored.
        departures.complete(0, resultOf(upcomingDeparture("stale-old-config")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LiveDeparturesState.Loading, vm.uiState.value.departures)

        // The NEW (post-edit configuration) fetch resolves -- this one must win.
        departures.complete(1, resultOf(upcomingDeparture("fresh")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("fresh"),
            (vm.uiState.value.departures as LiveDeparturesState.Live).snapshot.departures.map { it.departureId },
        )
    }

    // ---- Widget reconciliation (see se.blick.app.widget.RoutineWidgetUpdater.reconcile's own
    // doc on why every routine-lifecycle mutation site outside the active-window worker's loop
    // must call it) ----

    @Test
    fun `toggleEnabled reconciles the widget`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `pauseToday reconciles the widget`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `resumeToday reconciles the widget`() = runTest(dispatcher) {
        val routine = sampleRoutine(pausedDate = LocalDate.now(clock))
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `deleteRoutine reconciles the widget`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `reload reconciles the widget`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, routineId = "r1", routines = repository, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, widgetUpdater.reconcileCallCount)
    }

    // ---- Widget failures are best-effort: never make an already-successful action look failed,
    // never crash viewModelScope (see se.blick.app.widget.runWidgetUpdateSafely's own doc) ----

    @Test
    fun `toggleEnabled still succeeds and is never reported failed when the widget updater throws`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val vm = viewModel(routine = routine, widgetUpdater = FailingRoutineWidgetUpdater())
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        // The real mutation (setEnabled/scheduleActivation) already succeeded before the widget
        // updater ever ran -- a failure there must not overwrite this with enabledActionFailed.
        assertEquals(false, vm.uiState.value.enabledActionFailed)
        assertEquals(false, vm.uiState.value.isTogglingEnabled)
        assertEquals(false, vm.uiState.value.routine?.enabled)
    }

    @Test
    fun `pauseToday still succeeds and is never reported failed when the widget updater throws`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val vm = viewModel(routine = routine, widgetUpdater = FailingRoutineWidgetUpdater())
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.pauseActionFailed)
        assertEquals(false, vm.uiState.value.isTogglingPause)
        assertEquals(true, vm.uiState.value.isPausedToday)
    }

    @Test
    fun `resumeToday still succeeds and is never reported failed when the widget updater throws`() = runTest(dispatcher) {
        val routine = sampleRoutine(pausedDate = LocalDate.now(clock))
        val vm = viewModel(routine = routine, widgetUpdater = FailingRoutineWidgetUpdater())
        dispatcher.scheduler.advanceUntilIdle()

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.pauseActionFailed)
        assertEquals(false, vm.uiState.value.isTogglingPause)
        assertEquals(false, vm.uiState.value.isPausedToday)
    }

    @Test
    fun `deleteRoutine still succeeds, still calls onDeleted, and is never reported failed when the widget updater throws`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        var deleted = false
        val vm = viewModel(routine = routine, widgetUpdater = FailingRoutineWidgetUpdater())
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        // The strongest proof: onDeleted() only ever runs on the genuine success path -- if the
        // widget failure had leaked into the outer catch block, this would stay false and
        // deleteFailed would be true instead, even though the routine really was deleted.
        assertEquals(true, deleted)
        assertEquals(false, vm.uiState.value.deleteFailed)
        assertEquals(false, vm.uiState.value.isDeleting)
    }

    @Test
    fun `reload completes and refreshes the routine without crashing when the widget updater throws`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routineId = "r1", routines = repository, widgetUpdater = FailingRoutineWidgetUpdater())
        dispatcher.scheduler.advanceUntilIdle()

        repository.save(routine.copy(name = "Renamed"))
        // reload()'s widget call is wrapped with runWidgetUpdateSafely -- if that wrapping were
        // ever removed, the failure below would propagate as an uncaught exception and fail
        // this whole test (viewModelScope has no default exception handler). The test passing
        // at all is therefore itself part of the regression proof; the assertion below
        // additionally confirms the reload's own real work still completed.
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Renamed", vm.uiState.value.routine?.name)
    }

    // ---- Persistence vs. scheduling: once a Room write succeeds, a SUBSEQUENT scheduler
    // failure must never be reported as that action having failed, and the UI must reflect the
    // persisted truth regardless (see toggleEnabled/pauseToday/resumeToday/deleteRoutine/
    // reload's own class docs) ----

    @Test
    fun `toggleEnabled persists and updates the UI even when scheduling the change fails`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        // Room's write is the persisted truth -- the UI must reflect it, and this must NOT be
        // reported as a failed action, even though scheduleActivation below it threw.
        assertEquals(listOf(routine.id to false), repository.setEnabledCalls)
        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertEquals(false, vm.uiState.value.enabledActionFailed)
        assertEquals(true, vm.uiState.value.schedulingFailed)
        assertEquals(false, vm.uiState.value.isTogglingEnabled)
        assertEquals(1, scheduler.cancelCallCount) // disabling calls cancelActivation, not scheduleActivation
    }

    @Test
    fun `pauseToday persists and updates the UI even when scheduling the change fails`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        val today = LocalDate.now(clock)
        assertEquals(listOf(routine.id to today), repository.pauseForDateCalls)
        assertEquals(today, vm.uiState.value.routine?.pausedDate)
        assertEquals(true, vm.uiState.value.isPausedToday)
        assertEquals(false, vm.uiState.value.pauseActionFailed)
        assertEquals(true, vm.uiState.value.schedulingFailed)
        assertEquals(false, vm.uiState.value.isTogglingPause)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `resumeToday persists and updates the UI even when scheduling the change fails`() = runTest(dispatcher) {
        val routine = sampleRoutine(pausedDate = LocalDate.now(clock))
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(routine.id), repository.clearPauseCalls)
        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        assertEquals(false, vm.uiState.value.isPausedToday)
        assertEquals(false, vm.uiState.value.pauseActionFailed)
        assertEquals(true, vm.uiState.value.schedulingFailed)
        assertEquals(false, vm.uiState.value.isTogglingPause)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `deleteRoutine deletes and calls onDeleted even when cancelling its scheduled work fails`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        // Room deletion is the persisted truth -- a WorkManager cancellation failure on top of
        // an already-successful delete must never be reported as "delete failed", and the
        // caller must still be told to navigate away.
        assertEquals(listOf(routine.id), repository.deletedIds)
        assertEquals(true, deleted)
        assertEquals(false, vm.uiState.value.deleteFailed)
        // Deliberately not a schedulingFailed-with-retry case: there is no future occurrence
        // left to retry scheduling for once the routine itself is gone -- see deleteRoutine's
        // own class doc on relying on the worker's own existence check plus WorkManager's
        // eventual pruning instead.
        assertEquals(false, vm.uiState.value.schedulingFailed)
        assertEquals(false, vm.uiState.value.isDeleting)
        assertEquals(1, scheduler.cancelCallCount)
    }

    @Test
    fun `reload contains an ordinary Room read failure instead of crashing, keeping the previously displayed state`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1").copy(name = "Original")
        var shouldFail = false
        val repository = object : RoutineRepository {
            private val delegate = FakeRoutineRepository(routine)
            override fun observeAll(): Flow<List<CommuteRoutine>> = delegate.observeAll()
            override suspend fun getById(id: String): CommuteRoutine? {
                if (shouldFail) throw IOException("Room unavailable")
                return delegate.getById(id)
            }
            override suspend fun save(routine: CommuteRoutine) = delegate.save(routine)
            override suspend fun delete(id: String) = delegate.delete(id)
            override suspend fun pauseForDate(id: String, date: LocalDate) = delegate.pauseForDate(id, date)
            override suspend fun clearPause(id: String) = delegate.clearPause(id)
            override suspend fun setEnabled(id: String, enabled: Boolean) = delegate.setEnabled(id, enabled)
            override suspend fun hasAnyRoutine(): Boolean = delegate.hasAnyRoutine()
        }
        val vm = viewModel(routine = routine, routineId = "r1", routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Original", vm.uiState.value.routine?.name)

        shouldFail = true
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        // Must not crash (proven by this test completing at all -- viewModelScope has no
        // default exception handler), and the last genuinely valid state must still be shown
        // rather than being torn down because this reload attempt failed.
        assertEquals("Original", vm.uiState.value.routine?.name)
    }

    @Test
    fun `reload applies the freshly-loaded routine and reconciles the widget even when rescheduling it fails`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1").copy(name = "Original")
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, routineId = "r1", routines = repository, scheduler = scheduler, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        repository.save(routine.copy(name = "Renamed"))
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        // The freshly-read Room data is real and already correctly applied -- a scheduling
        // failure on top of it must not revert that, and the widget reconcile below it must
        // still be attempted independently.
        assertEquals("Renamed", vm.uiState.value.routine?.name)
        assertEquals(1, scheduler.scheduleCallCount)
        assertEquals(1, widgetUpdater.reconcileCallCount)
        assertEquals(true, vm.uiState.value.schedulingFailed)
    }

    // ---- retryScheduling: retries ONLY the scheduler, never repeats the already-successful
    // Room mutation, using whichever routine is currently persisted ----

    @Test
    fun `retryScheduling does not repeat the Room mutation`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        // Disabling: toggleEnabled's own branch calls cancelActivation directly.
        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repository.setEnabledCalls.size)
        assertEquals(1, scheduler.cancelCallCount)

        // retryScheduling always calls scheduleActivation (which itself internally cancels for
        // a disabled routine -- see WorkManagerRoutineScheduler's own doc), never
        // cancelActivation directly, and never repository.setEnabled again.
        vm.retryScheduling()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.setEnabledCalls.size)
        assertEquals(1, scheduler.cancelCallCount)
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `a successful retryScheduling uses the current persisted routine and clears schedulingFailed`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val scheduler = ToggleableRoutineScheduler(shouldFail = true)
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.schedulingFailed)
        val today = LocalDate.now(clock)

        scheduler.shouldFail = false
        vm.retryScheduling()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.schedulingFailed)
        assertEquals(false, vm.uiState.value.isRetryingScheduling)
        // The retry used the CURRENT persisted routine (pausedDate = today, from the earlier
        // pauseToday() call) -- not a stale or freshly-reconstructed one.
        assertEquals(listOf(today), scheduler.scheduledRoutines.map { it.pausedDate })
    }

    @Test
    fun `retryScheduling is a no-op when the routine has not loaded yet`() = runTest(dispatcher) {
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = null, routineId = "does-not-exist", scheduler = scheduler)

        vm.retryScheduling()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, scheduler.scheduleCallCount)
    }

    @Test
    fun `calling retryScheduling while a retry is already in flight only schedules once`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val scheduler = FailingRoutineScheduler()
        val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()
        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, scheduler.cancelCallCount)
        assertEquals(0, scheduler.scheduleCallCount)

        vm.retryScheduling()
        vm.retryScheduling()
        dispatcher.scheduler.advanceUntilIdle()

        // Exactly 1 scheduleActivation call from the two overlapping retryScheduling() calls,
        // not 2 -- the second call must see isRetryingScheduling already true and no-op.
        assertEquals(1, scheduler.scheduleCallCount)
    }

    @Test
    fun `a cancellation while retrying scheduling propagates instead of being treated as an ordinary failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(
            routine = routine, routines = repository,
            scheduler = FailingRoutineScheduler(throwCancellation = true),
            widgetUpdater = widgetUpdater,
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        val widgetCallsBeforeRetry = widgetUpdater.reconcileCallCount
        vm.retryScheduling()
        dispatcher.scheduler.advanceUntilIdle()

        // If the CancellationException from scheduleActivation had been wrongly caught as an
        // ordinary failure instead of rethrown, execution would have fallen through to the
        // widget reconcile call right after it -- it must not have.
        assertEquals(widgetCallsBeforeRetry, widgetUpdater.reconcileCallCount)
    }

    // ---- Coroutine cancellation is preserved across the now-separate persistence/scheduling
    // try/catch blocks (a genuine CancellationException must always propagate unconverted, never
    // be treated as an ordinary failure). Each of these makes the distinction OBSERVABLE by
    // checking whether execution fell through to the step immediately after the cancelled one
    // (the widget reconcile, or onDeleted) -- which must never happen on a genuine cancellation,
    // unlike an ordinary caught failure, which always still reaches it. ----

    @Test
    fun `a cancellation while scheduling toggleEnabled's change propagates instead of being treated as an ordinary failure`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, scheduler = FailingRoutineScheduler(throwCancellation = true), widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        // The Room write already succeeded and is reflected regardless.
        assertEquals(false, vm.uiState.value.routine?.enabled)
        assertEquals(false, vm.uiState.value.enabledActionFailed)
        // If the CancellationException from cancelActivation had been wrongly caught as an
        // ordinary failure instead of rethrown, execution would have fallen through to the
        // widget reconcile call right after it -- it must not have.
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `a cancellation while cancelling deleteRoutine's scheduled work propagates instead of being treated as an ordinary failure`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(routine)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(
            routine = routine, routines = repository,
            scheduler = FailingRoutineScheduler(throwCancellation = true),
            widgetUpdater = widgetUpdater,
        )
        dispatcher.scheduler.advanceUntilIdle()

        var deleted = false
        vm.deleteRoutine { deleted = true }
        dispatcher.scheduler.advanceUntilIdle()

        // Room deletion already succeeded regardless.
        assertEquals(listOf(routine.id), repository.deletedIds)
        // If the CancellationException from cancelActivation had been wrongly caught as an
        // ordinary failure instead of rethrown, execution would have fallen through to
        // routineNotifier.remove(), the widget reconcile, and onDeleted() -- none of that must
        // have happened; the whole coroutine ends cancelled at that point instead.
        assertEquals(false, deleted)
        assertEquals(false, vm.uiState.value.deleteFailed)
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `a cancellation while rescheduling in reload propagates instead of being treated as an ordinary failure`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(
            routine = routine, routineId = "r1", routines = repository,
            scheduler = FailingRoutineScheduler(throwCancellation = true),
            widgetUpdater = widgetUpdater,
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        // If the CancellationException from scheduleActivation had been wrongly caught by the
        // generic `catch (e: Exception)` instead of being rethrown, execution would have fallen
        // through to the widget reconcile call right after it -- it must not have.
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    @Test
    fun `a cancellation while reading the routine in reload propagates instead of being treated as an ordinary failure`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        var shouldCancel = false
        val repository = object : RoutineRepository {
            private val delegate = FakeRoutineRepository(routine)
            override fun observeAll(): Flow<List<CommuteRoutine>> = delegate.observeAll()
            override suspend fun getById(id: String): CommuteRoutine? {
                if (shouldCancel) throw CancellationException("test cancellation")
                return delegate.getById(id)
            }
            override suspend fun save(routine: CommuteRoutine) = delegate.save(routine)
            override suspend fun delete(id: String) = delegate.delete(id)
            override suspend fun pauseForDate(id: String, date: LocalDate) = delegate.pauseForDate(id, date)
            override suspend fun clearPause(id: String) = delegate.clearPause(id)
            override suspend fun setEnabled(id: String, enabled: Boolean) = delegate.setEnabled(id, enabled)
            override suspend fun hasAnyRoutine(): Boolean = delegate.hasAnyRoutine()
        }
        val widgetUpdater = FakeRoutineWidgetUpdater()
        val vm = viewModel(routine = routine, routineId = "r1", routines = repository, widgetUpdater = widgetUpdater)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, widgetUpdater.reconcileCallCount) // nothing from init's own load -- reload() is what's under test

        shouldCancel = true
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        // If the CancellationException from getById had been wrongly caught by the generic
        // `catch (e: Exception)` instead of being rethrown, execution would eventually have
        // reached the widget reconcile call further down -- it must not have.
        assertEquals(0, widgetUpdater.reconcileCallCount)
    }

    // ---- Stale-snapshot identity regression (edit to a new site/line/direction/mode must
    // never let the OLD routine's cached snapshot resurface as "stale" data for the NEW one)

    @Test
    fun `a failed first fetch for an edited routine's new identity does not resurface the old routine's departures as stale`() = runTest(dispatcher) {
        val routineA = sampleRoutine(id = "r1", siteId = 9145)
        val repository = FakeRoutineRepository(routineA)
        val departures = PerSiteDepartureRepository(
            resultsBySite = mapOf(9145L to resultOf(upcomingDeparture("a-dep"))),
            failingSites = setOf(9192L),
        )
        val vm = viewModel(routine = routineA, routineId = "r1", departures = departures, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()

        // Sanity: routine A's own fetch genuinely succeeded and is cached.
        val initial = vm.uiState.value.departures
        assertTrue(initial is LiveDeparturesState.Live)
        assertEquals(listOf("a-dep"), (initial as LiveDeparturesState.Live).snapshot.departures.map { it.departureId })

        // Edit routine A to a different site (a new departure identity) -- simulates what
        // RoutineCreateViewModel's save() does for an existing id.
        val routineB = routineA.copy(siteId = 9192, siteName = "Slussen")
        repository.save(routineB)
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        // Routine B's first-ever fetch genuinely fails with no valid (same-identity) cached
        // snapshot of its own -- this must surface as a real failure state, never as Stale
        // wrapping routine A's unrelated departures.
        val afterFailedReload = vm.uiState.value.departures
        assertTrue(
            "expected a non-Stale failure state (no valid snapshot for the new identity), got $afterFailedReload",
            afterFailedReload is LiveDeparturesState.Offline,
        )
        assertEquals("Slussen", vm.uiState.value.routine?.siteName)
    }

    @Test
    fun `a refresh failure for the SAME departure identity still falls back to its own cached snapshot`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1", siteId = 9145)
        val departures = ToggleableDepartureRepository(resultOf(upcomingDeparture("kept")))
        val vm = viewModel(routine = routine, routineId = "r1", departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.departures is LiveDeparturesState.Live)

        // A later manual refresh of the exact same routine/identity fails -- this is the
        // legitimate case the identity check must still allow through to Stale.
        departures.shouldFail = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value.departures
        assertTrue(state is LiveDeparturesState.Stale)
        assertEquals(listOf("kept"), (state as LiveDeparturesState.Stale).snapshot.departures.map { it.departureId })
    }

    // ---- Debug-only manual notification trigger (Part 6 / Fix 3) ----

    @Test
    fun `showDebugTestNotification maps the already-loaded routine and departures without a new fetch`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture("dep-a")))
        val notifier = FakeRoutineNotifier()
        val vm = viewModel(departures = departures, notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        vm.showDebugTestNotification()

        assertEquals(1, departures.callCount) // no new fetch was triggered
        assertEquals(1, notifier.shown.size)
        assertEquals(vm.uiState.value.routine?.id, notifier.shown.single().routineId)
    }

    @Test
    fun `showDebugTestNotification is a no-op before the routine has loaded`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier()
        val departures = ControllableDepartureRepository()
        viewModel(departures = departures, notifier = notifier).showDebugTestNotification()

        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun `showDebugTestNotification returns null before the routine has loaded`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier()
        val departures = ControllableDepartureRepository()
        val result = viewModel(departures = departures, notifier = notifier).showDebugTestNotification()

        assertNull(result)
    }

    @Test
    fun `showDebugTestNotification returns Posted when the notifier actually posts`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier(resultToReturn = NotificationPostResult.Posted)
        val vm = viewModel(notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()

        val result = vm.showDebugTestNotification()

        assertEquals(NotificationPostResult.Posted, result)
    }

    @Test
    fun `showDebugTestNotification propagates NotificationsDisabled from the notifier without posting`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier(resultToReturn = NotificationPostResult.NotificationsDisabled)
        val vm = viewModel(notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()

        val result = vm.showDebugTestNotification()

        assertEquals(NotificationPostResult.NotificationsDisabled, result)
    }

    @Test
    fun `showDebugTestNotification propagates Failed from the notifier`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier(resultToReturn = NotificationPostResult.Failed)
        val vm = viewModel(notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()

        val result = vm.showDebugTestNotification()

        assertEquals(NotificationPostResult.Failed, result)
    }

    @Test
    fun `removeDebugTestNotification calls through to the notifier`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier()
        val vm = viewModel(notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()

        vm.removeDebugTestNotification()

        assertEquals(1, notifier.removeCallCount)
    }

    @Test
    fun `isLiveUpdatePromotable reflects the checker`() = runTest(dispatcher) {
        val checker = FakePromotedNotificationChecker(promotable = true)
        val vm = viewModel(promotedNotificationChecker = checker)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.isLiveUpdatePromotable())

        checker.promotable = false
        assertFalse(vm.isLiveUpdatePromotable())
    }

    // ---- Automatic 30-second refresh (runAutoRefresh) ----
    //
    // dispatcher.scheduler (StandardTestDispatcher's virtual-time TestCoroutineScheduler) is
    // advanced explicitly throughout this section instead of ever waiting a real 30 seconds —
    // see RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS's own doc comment on why no separate
    // Ticker abstraction is needed for this.

    @Test
    fun `runAutoRefresh's first-ever call does not duplicate the initial fetch already triggered by loading the routine`() =
        runTest(dispatcher) {
            val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
            val vm = viewModel(departures = departures)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, departures.callCount) // init's own immediate fetch

            val job = launch { vm.runAutoRefresh() }
            // runCurrent() (not advanceUntilIdle()) -- the loop is `while (isActive) { delay(30s)
            // ...}` and never stops on its own, so advanceUntilIdle() here would free-run forever
            // (it only returns once genuinely idle, which this loop never reaches while active).
            // runCurrent() drains everything scheduled at the current virtual time -- enough to
            // let the coroutine reach its first real suspension point (the delay) -- without
            // advancing past it. Same pattern as RoutineActiveWindowWorkerTest's identical comment.
            dispatcher.scheduler.runCurrent()

            assertEquals(1, departures.callCount) // no extra fetch from starting the loop itself
            job.cancel()
        }

    @Test
    fun `runAutoRefresh fetches again after 30 seconds`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        val job = launch { vm.runAutoRefresh() }
        // See `runAutoRefresh's first-ever call...`'s comment on why this is runCurrent(), not
        // advanceUntilIdle() -- the loop is still active at both points below.
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals(2, departures.callCount)
        job.cancel()
    }

    @Test
    fun `an automatic refresh tick never blanks the section back to Loading`() = runTest(dispatcher) {
        val departures = ControllableDepartureRepository()
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        departures.complete(0, resultOf(upcomingDeparture()))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.departures is LiveDeparturesState.Live)

        val job = launch { vm.runAutoRefresh() }
        dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
        // runCurrent(), not advanceUntilIdle() -- the loop is still active (see
        // `runAutoRefresh's first-ever call...`'s comment).
        dispatcher.scheduler.runCurrent() // reach the automatic tick's own suspension point

        // Unlike the very first (INITIAL) fetch, an AUTOMATIC tick never resets the section to
        // Loading while its own fetch is in flight -- the previous Live data stays visible.
        assertNotEquals(LiveDeparturesState.Loading, vm.uiState.value.departures)
        departures.complete(1, resultOf(upcomingDeparture()))
        dispatcher.scheduler.runCurrent()
        job.cancel()
    }

    @Test
    fun `cancelling the auto-refresh coroutine stops further ticks`() = runTest(dispatcher) {
        val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
        val vm = viewModel(departures = departures)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, departures.callCount)

        val job = launch { vm.runAutoRefresh() }
        // runCurrent() while the loop is still active (see `runAutoRefresh's first-ever
        // call...`'s comment); advanceUntilIdle() below is fine once it's actually cancelled.
        dispatcher.scheduler.runCurrent()
        job.cancel()
        dispatcher.scheduler.advanceUntilIdle()

        dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS * 3)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, departures.callCount) // no ticks fired after cancellation
    }

    @Test
    fun `restarting runAutoRefresh after being stopped fetches immediately again, without waiting 30 seconds`() =
        runTest(dispatcher) {
            val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
            val vm = viewModel(departures = departures)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, departures.callCount)

            val firstJob = launch { vm.runAutoRefresh() }
            // runCurrent() while active; advanceUntilIdle() below is fine once cancelled (see
            // `runAutoRefresh's first-ever call...`'s comment).
            dispatcher.scheduler.runCurrent()
            firstJob.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, departures.callCount)

            val secondJob = launch { vm.runAutoRefresh() }
            dispatcher.scheduler.runCurrent()

            assertEquals(2, departures.callCount) // the restart's own immediate fetch
            secondJob.cancel()
        }

    @Test
    fun `calling runAutoRefresh while a loop is already active does not start a second concurrent loop`() =
        runTest(dispatcher) {
            val departures = FakeDepartureRepository(resultOf(upcomingDeparture()))
            val vm = viewModel(departures = departures)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, departures.callCount)

            val firstJob = launch { vm.runAutoRefresh() }
            // runCurrent() throughout -- both loops are still active (see `runAutoRefresh's
            // first-ever call...`'s comment).
            dispatcher.scheduler.runCurrent()
            // e.g. a rapid double recomposition re-entering repeatOnLifecycle before the first
            // call's own `finally` has cleared autoRefreshJob -- must be a no-op, not a second
            // concurrent loop.
            val secondJob = launch { vm.runAutoRefresh() }
            dispatcher.scheduler.runCurrent()

            dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
            dispatcher.scheduler.runCurrent()

            // Exactly one tick's worth of extra fetches (init's 1 + one tick = 2) -- two
            // concurrent loops would have produced two ticks (3 total) instead.
            assertEquals(2, departures.callCount)
            firstJob.cancel()
            secondJob.cancel()
        }

    // ---- Notification availability refreshes on lifecycle resume (Fix 3) ----

    @Test
    fun `notification availability begins reflecting the checker, changes while stopped are picked up on resume`() =
        runTest(dispatcher) {
            val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.AppDisabled)
            val vm = viewModel(notificationAvailabilityChecker = checker)
            dispatcher.scheduler.advanceUntilIdle()

            // Begins disabled -- the very first check, from init, before any lifecycle
            // resume/runAutoRefresh call at all.
            assertEquals(NotificationAvailability.AppDisabled, vm.uiState.value.notificationAvailability)

            // The screen is "stopped" (no runAutoRefresh loop running) while the underlying
            // availability changes -- e.g. the user granted notifications from system Settings.
            checker.current = NotificationAvailability.Available

            // A lifecycle resume: repeatOnLifecycle(STARTED) calling runAutoRefresh() again.
            val job = launch { vm.runAutoRefresh() }
            // runCurrent(), not advanceUntilIdle() -- see `runAutoRefresh's first-ever call...`'s
            // comment on why advanceUntilIdle() free-runs forever while this loop is active.
            dispatcher.scheduler.runCurrent()

            assertEquals(NotificationAvailability.Available, vm.uiState.value.notificationAvailability)
            job.cancel()
        }

    @Test
    fun `notification availability changing to disabled while stopped is also picked up on resume`() =
        runTest(dispatcher) {
            val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.Available)
            val vm = viewModel(notificationAvailabilityChecker = checker)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(NotificationAvailability.Available, vm.uiState.value.notificationAvailability)

            // Stopped, then the user disables the Blick channel specifically while away.
            checker.current = NotificationAvailability.ChannelDisabled

            val job = launch { vm.runAutoRefresh() }
            // runCurrent() -- see `runAutoRefresh's first-ever call...`'s comment.
            dispatcher.scheduler.runCurrent()

            assertEquals(NotificationAvailability.ChannelDisabled, vm.uiState.value.notificationAvailability)
            job.cancel()
        }

    @Test
    fun `markNotificationRationaleSeen also refreshes notification availability`() = runTest(dispatcher) {
        val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.PermissionMissing)
        val vm = viewModel(notificationAvailabilityChecker = checker)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(NotificationAvailability.PermissionMissing, vm.uiState.value.notificationAvailability)

        // The permission result callback flow always calls markNotificationRationaleSeen()
        // (granted, denied, or dismissed -- see rememberNotificationPermissionGate's finishAndRun).
        checker.current = NotificationAvailability.Available
        vm.markNotificationRationaleSeen()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(NotificationAvailability.Available, vm.uiState.value.notificationAvailability)
    }

    // ---- The routine's own enabled/paused state refreshes on lifecycle resume, same as
    // notification availability above -- see RoutineDetailsViewModel.refreshRoutineState's own
    // doc. Without this, an already-alive instance of this screen never notices a pause written
    // by StopRoutineNotificationAction (the ongoing notification's Stop action) while the screen
    // was merely backgrounded, not destroyed.

    @Test
    fun `a pause written externally while stopped is picked up on resume, without scheduling anything`() =
        runTest(dispatcher) {
            val routine = sampleRoutine()
            val repository = FakeRoutineRepository(routine)
            val scheduler = FakeRoutineScheduler()
            val vm = viewModel(routine = routine, routines = repository, scheduler = scheduler)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.isPausedToday)

            // The screen is "stopped" (no runAutoRefresh loop running) while something OUTSIDE
            // it pauses the routine -- e.g. StopRoutineNotificationAction.stop(), triggered by
            // tapping Stop on the ongoing notification.
            val today = LocalDate.now(clock)
            repository.pauseForDate(routine.id, today)

            // A lifecycle resume: repeatOnLifecycle(STARTED) calling runAutoRefresh() again.
            val job = launch { vm.runAutoRefresh() }
            // runCurrent(), not advanceUntilIdle() -- see `runAutoRefresh's first-ever call...`'s
            // comment on why advanceUntilIdle() free-runs forever while this loop is active.
            dispatcher.scheduler.runCurrent()

            assertTrue(vm.uiState.value.isPausedToday)
            assertEquals(today, vm.uiState.value.routine?.pausedDate)
            // A mere reactivation is not an edit -- unlike reload(), this must never call the
            // scheduler (see refreshRoutineState's own doc).
            assertTrue(scheduler.scheduledRoutines.isEmpty())
            job.cancel()
        }

    @Test
    fun `a resume written externally while stopped is also picked up on resume`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isPausedToday)

        // Stopped, then something outside this screen resumes the routine.
        repository.clearPause(routine.id)

        val job = launch { vm.runAutoRefresh() }
        // runCurrent() -- see `runAutoRefresh's first-ever call...`'s comment.
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.isPausedToday)
        assertEquals(null, vm.uiState.value.routine?.pausedDate)
        job.cancel()
    }

    @Test
    fun `an enabled flag changed externally while stopped is also picked up on resume`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val repository = FakeRoutineRepository(routine)
        val vm = viewModel(routine = routine, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.routine?.enabled)

        repository.setEnabled(routine.id, false)

        val job = launch { vm.runAutoRefresh() }
        dispatcher.scheduler.runCurrent()

        assertEquals(false, vm.uiState.value.routine?.enabled)
        job.cancel()
    }

    // ---- Notification-recovery reporting only (RoutineDetailsViewModel is no longer an
    // independent scheduling authority) ----
    //
    // se.blick.app.scheduling.NotificationRecoveryCoordinator is now the SOLE authority for
    // deciding when to reschedule/reconcile after notifications become available again (see
    // that class's own doc). This screen's only remaining responsibility is durably REPORTING
    // an observed unavailable state via NotificationRecoveryReporter -- it must never call
    // RoutineScheduler.scheduleActivation or reconcile the widget itself, since two independent
    // code paths racing to schedule the same routine's activation is exactly the bug the
    // coordinator was introduced to fix.

    @Test
    fun `refreshNotificationAvailability reports unavailable and never schedules or reconciles the widget itself`() =
        runTest(dispatcher) {
            val routine = sampleRoutine(enabled = true)
            val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.Available)
            val scheduler = FakeRoutineScheduler()
            val widgetUpdater = FakeRoutineWidgetUpdater()
            val reporter = FakeNotificationRecoveryReporter()
            val vm = viewModel(
                routine = routine,
                notificationAvailabilityChecker = checker,
                scheduler = scheduler,
                widgetUpdater = widgetUpdater,
                notificationRecoveryReporter = reporter,
            )
            dispatcher.scheduler.advanceUntilIdle()
            // The very first check (in init) is Available -- nothing to report.
            assertEquals(0, reporter.reportUnavailableCallCount)

            checker.current = NotificationAvailability.AppDisabled
            vm.refreshNotificationAvailability()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(NotificationAvailability.AppDisabled, vm.uiState.value.notificationAvailability)
            assertEquals(1, reporter.reportUnavailableCallCount)
            // Scheduling/reconciling a recovered routine is the coordinator's job now, never
            // this ViewModel's -- see this section's own doc.
            assertTrue(scheduler.scheduledRoutines.isEmpty())
            assertEquals(0, widgetUpdater.reconcileCallCount)
        }

    @Test
    fun `refreshNotificationAvailability does not report when availability is already available`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.Available)
        val reporter = FakeNotificationRecoveryReporter()
        val vm = viewModel(routine = routine, notificationAvailabilityChecker = checker, notificationRecoveryReporter = reporter)
        dispatcher.scheduler.advanceUntilIdle()

        vm.refreshNotificationAvailability()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, reporter.reportUnavailableCallCount)
    }

    @Test
    fun `refreshNotificationAvailability reports unavailable even for a disabled routine`() =
        runTest(dispatcher) {
            // The report is purely "notifications are unavailable right now" -- whether that's
            // worth acting on for THIS routine is the coordinator's decision (it only ever
            // touches enabled routines), not something this screen needs to pre-filter.
            val routine = sampleRoutine(enabled = false)
            val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.AppDisabled)
            val reporter = FakeNotificationRecoveryReporter()
            val vm = viewModel(routine = routine, notificationAvailabilityChecker = checker, notificationRecoveryReporter = reporter)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, reporter.reportUnavailableCallCount)
        }

    @Test
    fun `markNotificationRationaleSeen also reports unavailable, never schedules`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val checker = FakeNotificationAvailabilityChecker(current = NotificationAvailability.Available)
        val scheduler = FakeRoutineScheduler()
        val reporter = FakeNotificationRecoveryReporter()
        val vm = viewModel(
            routine = routine,
            notificationAvailabilityChecker = checker,
            scheduler = scheduler,
            notificationRecoveryReporter = reporter,
        )
        dispatcher.scheduler.advanceUntilIdle()

        checker.current = NotificationAvailability.PermissionMissing
        vm.markNotificationRationaleSeen()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, reporter.reportUnavailableCallCount)
        assertTrue(scheduler.scheduledRoutines.isEmpty())
    }

    // ---- Scheduler integration (save/enable/disable/pause/resume/delete) ----

    @Test
    fun `enabling a routine schedules its next activation`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = false)
        val scheduler = FakeRoutineScheduler()
        val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(true), scheduler.scheduledRoutines.map { it.enabled })
        assertTrue(scheduler.cancelledRoutineIds.isEmpty())
    }

    @Test
    fun `disabling a routine cancels its scheduled activation`() = runTest(dispatcher) {
        val routine = sampleRoutine(enabled = true)
        val scheduler = FakeRoutineScheduler()
        val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleEnabled()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(routine.id), scheduler.cancelledRoutineIds)
        assertTrue(scheduler.scheduledRoutines.isEmpty())
    }

    @Test
    fun `pausing today reschedules excluding today`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val scheduler = FakeRoutineScheduler()
        val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.pauseToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(LocalDate.now(clock)), scheduler.scheduledRoutines.map { it.pausedDate })
    }

    @Test
    fun `resuming today reschedules without an excluded date`() = runTest(dispatcher) {
        val today = LocalDate.now(clock)
        val routine = sampleRoutine(pausedDate = today)
        val scheduler = FakeRoutineScheduler()
        val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), scheduler = scheduler)
        dispatcher.scheduler.advanceUntilIdle()

        vm.resumeToday()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf<LocalDate?>(null), scheduler.scheduledRoutines.map { it.pausedDate })
    }

    @Test
    fun `deleting a routine cancels its scheduled activation and removes its notification`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val scheduler = FakeRoutineScheduler()
        val notifier = FakeRoutineNotifier()
        val vm = viewModel(
            routine = routine,
            routines = FakeRoutineRepository(routine),
            scheduler = scheduler,
            notifier = notifier,
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteRoutine {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(routine.id), scheduler.cancelledRoutineIds)
        assertEquals(1, notifier.removeCallCount)
    }

    // ---- Disruptions section (independent of departures) ----

    @Test
    fun `the disruptions fetch begins automatically once the routine has loaded`() = runTest(dispatcher) {
        val disruptions = FakeDisruptionRepository(listOf(sampleDisruption()))
        viewModel(disruptions = disruptions)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, disruptions.callCount)
    }

    @Test
    fun `Loading is shown until the disruptions fetch resolves, then Loaded`() = runTest(dispatcher) {
        val disruptions = ControllableDisruptionRepository()
        val vm = viewModel(disruptions = disruptions)

        dispatcher.scheduler.advanceUntilIdle() // reach the fetch's suspension point
        assertEquals(DisruptionsState.Loading, vm.uiState.value.disruptions)

        disruptions.complete(0, listOf(sampleDisruption()))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disruptions is DisruptionsState.Loaded)
    }

    @Test
    fun `a Loaded state exposes the repository's own disruptions list`() = runTest(dispatcher) {
        val a = sampleDisruption("a")
        val b = sampleDisruption("b")
        val vm = viewModel(disruptions = FakeDisruptionRepository(listOf(a, b)))
        dispatcher.scheduler.advanceUntilIdle()

        val loaded = vm.uiState.value.disruptions as DisruptionsState.Loaded
        assertEquals(listOf("a", "b"), loaded.disruptions.map { it.disruptionId })
    }

    @Test
    fun `an empty result produces NoDisruptions`() = runTest(dispatcher) {
        val vm = viewModel(disruptions = FakeDisruptionRepository(emptyList()))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DisruptionsState.NoDisruptions, vm.uiState.value.disruptions)
    }

    @Test
    fun `a disruptions fetch failure produces Unavailable`() = runTest(dispatcher) {
        val vm = viewModel(disruptions = FailingDisruptionRepository(RuntimeException("boom")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DisruptionsState.Unavailable, vm.uiState.value.disruptions)
    }

    @Test
    fun `a disruptions failure never touches the departures state`() = runTest(dispatcher) {
        val vm = viewModel(
            departures = FakeDepartureRepository(resultOf(upcomingDeparture())),
            disruptions = FailingDisruptionRepository(RuntimeException("boom")),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.departures is LiveDeparturesState.Live)
        assertEquals(DisruptionsState.Unavailable, vm.uiState.value.disruptions)
    }

    @Test
    fun `a departures failure never touches the disruptions state`() = runTest(dispatcher) {
        val vm = viewModel(
            departures = FailingDepartureRepository(RuntimeException("boom")),
            disruptions = FakeDisruptionRepository(listOf(sampleDisruption())),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LiveDeparturesState.Unavailable, vm.uiState.value.departures)
        assertTrue(vm.uiState.value.disruptions is DisruptionsState.Loaded)
    }

    @Test
    fun `refresh also triggers a new disruptions fetch`() = runTest(dispatcher) {
        val disruptions = FakeDisruptionRepository(listOf(sampleDisruption()))
        val vm = viewModel(disruptions = disruptions)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, disruptions.callCount)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, disruptions.callCount)
    }

    @Test
    fun `an automatic refresh tick never blanks disruptions back to Loading`() = runTest(dispatcher) {
        val disruptions = ControllableDisruptionRepository()
        val vm = viewModel(disruptions = disruptions)
        dispatcher.scheduler.advanceUntilIdle()
        disruptions.complete(0, listOf(sampleDisruption()))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.disruptions is DisruptionsState.Loaded)

        val job = launch { vm.runAutoRefresh() }
        dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertNotEquals(DisruptionsState.Loading, vm.uiState.value.disruptions)
        disruptions.complete(1, listOf(sampleDisruption()))
        dispatcher.scheduler.runCurrent()
        job.cancel()
    }

    @Test
    fun `reload re-fetches disruptions once the routine's identity changes`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r1")
        val repository = FakeRoutineRepository(routine)
        val disruptions = FakeDisruptionRepository(listOf(sampleDisruption()))
        val vm = viewModel(routine = routine, routineId = "r1", disruptions = disruptions, routines = repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, disruptions.callCount)

        repository.save(routine.copy(siteId = 9192, siteName = "Slussen"))
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, disruptions.callCount)
    }

    @Test
    fun `an older in-flight disruptions fetch cannot overwrite a newer one`() = runTest(dispatcher) {
        val disruptions = ControllableDisruptionRepository()
        val vm = viewModel(disruptions = disruptions)
        dispatcher.scheduler.advanceUntilIdle()
        disruptions.complete(0, listOf(sampleDisruption("initial")))
        dispatcher.scheduler.advanceUntilIdle()

        vm.refresh() // call index 1 (older refresh), left pending
        dispatcher.scheduler.advanceUntilIdle()
        vm.refresh() // call index 2 (newer refresh) -- must supersede index 1
        dispatcher.scheduler.advanceUntilIdle()

        disruptions.complete(2, listOf(sampleDisruption("newer")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("newer"), (vm.uiState.value.disruptions as DisruptionsState.Loaded).disruptions.map { it.disruptionId })

        // The older, superseded refresh finally resolves late -- must be ignored.
        disruptions.complete(1, listOf(sampleDisruption("stale-older")))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("newer"), (vm.uiState.value.disruptions as DisruptionsState.Loaded).disruptions.map { it.disruptionId })
    }

    @Test
    fun `showDebugTestNotification carries the highest-priority already-loaded disruption`() = runTest(dispatcher) {
        val notifier = FakeRoutineNotifier()
        val vm = viewModel(disruptions = FakeDisruptionRepository(listOf(sampleDisruption("d1"))), notifier = notifier)
        dispatcher.scheduler.advanceUntilIdle()

        vm.showDebugTestNotification()

        assertEquals("Header d1", notifier.shown.single().disruptionHeadline)
    }

    // ---- journeysEvaluatedAt: makes a completed automatic refresh observable to StateFlow even
    // when the newly-fetched journeys are structurally identical to what was already displayed
    // (see RoutineDetailsUiState.journeysEvaluatedAt's own doc) ----

    /** A settable [Clock] the test advances explicitly BETWEEN consecutive automatic fetches --
     * unlike the file-level fixed [clock], this lets a test assert on what changes (or doesn't)
     * as real time passes across refreshes, without any real sleep or wall-clock dependency. */
    private class SettableClock(startInstant: Instant, private val zoneId: ZoneId = ZoneOffset.UTC) : Clock() {
        var instant: Instant = startInstant
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zoneId: ZoneId): Clock = SettableClock(instant, zoneId)
        override fun instant(): Instant = instant
    }

    /** Always returns the SAME [journeys] list, however many times [getJourneys] is called --
     * models the real "identical structurally-equal response on the next automatic refresh"
     * scenario [RoutineDetailsUiState.journeysEvaluatedAt] exists to make observable. */
    private class FixedJourneyRepository(private val journeys: List<JourneyPlan>) : JourneyRepository {
        var callCount = 0
        override suspend fun searchLocations(query: String): List<JourneyLocation> = emptyList()
        override suspend fun getJourneys(
            originId: String,
            destinationId: String,
            allowedTransportModes: Set<TransportMode>,
        ): List<JourneyPlan> {
            callCount++
            return journeys
        }
    }

    private fun exactDestinationRoutine(id: String = "r1") = sampleRoutine(id = id).copy(
        type = RoutineType.EXACT_DESTINATION,
        transportMode = TransportMode.UNKNOWN,
        lineId = null,
        lineDesignation = null,
        directionCode = null,
        destinationLabel = null,
        journeyOriginId = "origin-id",
        journeyOriginName = "Fruängen",
        journeyDestinationId = "destination-id",
        journeyDestinationName = "Arlanda",
    )

    private fun journeyPlan(id: String, departure: Instant, lineDesignation: String = "14") = run {
        val leg = JourneyLeg(
            TransportMode.METRO, lineDesignation, "Direction", "Fruängen", "Arlanda",
            departure, departure.plusSeconds(600), true, emptyList(),
        )
        JourneyPlan(id, "Fruängen", "Arlanda", departure, departure.plusSeconds(600), 0, leg, listOf(leg), emptyList())
    }

    @Test
    fun `journeysEvaluatedAt advances on an identical automatic refresh, and the countdown-relevant timestamp reflects it`() =
        runTest(dispatcher) {
            // Journey departs at 08:04:30; the first fetch is evaluated at 08:00:00 (4m30s away,
            // ceiling-rounds to 5 min) and the second, identical fetch at 08:00:30 (4m exactly).
            val departure = Instant.parse("2026-07-28T08:04:30Z")
            val testClock = SettableClock(Instant.parse("2026-07-28T08:00:00Z"))
            val journey = journeyPlan("journey-1", departure)
            val journeyRepository = FixedJourneyRepository(listOf(journey))
            val getRankedJourneys = GetRankedJourneysUseCase(journeyRepository, testClock)
            val routine = exactDestinationRoutine()
            val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), clock = testClock, getRankedJourneys = getRankedJourneys)
            dispatcher.scheduler.advanceUntilIdle()

            val firstJourneys = vm.uiState.value.journeys
            val firstEvaluatedAt = vm.uiState.value.journeysEvaluatedAt
            assertEquals(listOf("journey-1"), firstJourneys.map { it.journeyId })
            assertEquals(Instant.parse("2026-07-28T08:00:00Z"), firstEvaluatedAt)
            assertEquals(5L, countdownMinutes(firstEvaluatedAt, departure))

            // Real time passes between refreshes -- the test clock is advanced exactly like a
            // real Clock would have moved on its own; the fake repository keeps returning the
            // SAME (structurally equal) journey list regardless.
            testClock.instant = Instant.parse("2026-07-28T08:00:30Z")
            val job = launch { vm.runAutoRefresh() }
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
            dispatcher.scheduler.runCurrent()

            assertEquals(2, journeyRepository.callCount)
            val secondJourneys = vm.uiState.value.journeys
            val secondEvaluatedAt = vm.uiState.value.journeysEvaluatedAt
            // The journey list itself is structurally equal -- proving this is genuinely the
            // "identical response" scenario journeysEvaluatedAt exists for, not a coincidentally
            // different one.
            assertEquals(firstJourneys, secondJourneys)
            // journeysEvaluatedAt still moved forward -- StateFlow's own `data class` equality
            // check on the WHOLE state therefore sees a real change and emits, even though
            // `journeys` alone did not change.
            assertEquals(Instant.parse("2026-07-28T08:00:30Z"), secondEvaluatedAt)
            assertTrue(secondEvaluatedAt.isAfter(firstEvaluatedAt))
            // The countdown a recomposed JourneyComparisonSection would compute from this new
            // timestamp has genuinely advanced: 5 min -> 4 min, for the exact same journey.
            assertEquals(4L, countdownMinutes(secondEvaluatedAt, departure))

            job.cancel()
        }

    @Test
    fun `once the evaluation time passes the departure, journeys becomes empty rather than keeping an expired entry`() =
        runTest(dispatcher) {
            val departure = Instant.parse("2026-07-28T08:04:30Z")
            val testClock = SettableClock(Instant.parse("2026-07-28T08:00:00Z"))
            val journey = journeyPlan("journey-1", departure)
            val journeyRepository = FixedJourneyRepository(listOf(journey))
            val getRankedJourneys = GetRankedJourneysUseCase(journeyRepository, testClock)
            val routine = exactDestinationRoutine()
            val vm = viewModel(routine = routine, routines = FakeRoutineRepository(routine), clock = testClock, getRankedJourneys = getRankedJourneys)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("journey-1"), vm.uiState.value.journeys.map { it.journeyId })

            // The evaluation instant is now strictly after the journey's own departure.
            testClock.instant = departure.plusSeconds(1)
            val job = launch { vm.runAutoRefresh() }
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(RoutineDetailsViewModel.AUTO_REFRESH_INTERVAL_MS + 1)
            dispatcher.scheduler.runCurrent()

            // GetRankedJourneysUseCase's own defensive filter already removes it -- the card must
            // disappear (the existing no-journeys state), never remain visible at "0 min".
            assertEquals(emptyList<JourneyPlan>(), vm.uiState.value.journeys)
            assertFalse(vm.uiState.value.journeysUnavailable)
            assertEquals(departure.plusSeconds(1), vm.uiState.value.journeysEvaluatedAt)

            job.cancel()
        }

}
