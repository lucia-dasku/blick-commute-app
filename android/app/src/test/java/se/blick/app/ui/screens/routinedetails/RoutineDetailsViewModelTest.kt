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
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.GetLiveDeparturesUseCase
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.RoutineNotificationModel
import se.blick.app.notification.RoutineNotifier
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.ui.navigation.Routes
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
        siteDeviations = emptyList(),
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
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
            callCount++
            return result
        }
    }

    private class FailingDepartureRepository(private val error: Throwable) : DepartureRepository {
        override suspend fun getDepartures(siteId: Long): DeparturesResult = throw error
    }

    /** Succeeds with [result] while [shouldFail] is false; throws [failure] once flipped on
     * — for proving a later refresh failure falls back to Stale using the earlier success. */
    private class ToggleableDepartureRepository(
        private val result: DeparturesResult,
        var shouldFail: Boolean = false,
        private val failure: Throwable = IOException("network down"),
    ) : DepartureRepository {
        var callCount = 0
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
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
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
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
        override suspend fun getDepartures(siteId: Long): DeparturesResult {
            val deferred = CompletableDeferred<DeparturesResult>()
            pending += deferred
            return deferred.await()
        }
        fun complete(callIndex: Int, result: DeparturesResult) {
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
        routines: RoutineRepository = FakeRoutineRepository(routine),
        staleSnapshots: StaleSnapshotRepository = FakeStaleSnapshotRepository(),
        notifier: RoutineNotifier = FakeRoutineNotifier(),
        scheduler: RoutineScheduler = FakeRoutineScheduler(),
        appSettingsDataStore: AppSettingsDataStore = FakeAppSettingsDataStore(),
        notificationAvailabilityChecker: NotificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
    ) = RoutineDetailsViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to routineId)),
        routineRepository = routines,
        getLiveDepartures = GetLiveDeparturesUseCase(departures, clock),
        staleSnapshotRepository = staleSnapshots,
        routineNotifier = notifier,
        routineScheduler = scheduler,
        appSettingsDataStore = appSettingsDataStore,
        notificationAvailabilityChecker = notificationAvailabilityChecker,
        clock = clock,
    )

    // ---- Routine loading ----

    @Test
    fun `the correct routine is loaded using the navigation id`() = runTest(dispatcher) {
        val routine = sampleRoutine(id = "r42")
        val repository = FakeRoutineRepository(routine)
        val vm = RoutineDetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.RoutineDetails.ARG_ROUTINE_ID to "r42")),
            routineRepository = repository,
            getLiveDepartures = GetLiveDeparturesUseCase(FakeDepartureRepository(resultOf(upcomingDeparture())), clock),
            staleSnapshotRepository = FakeStaleSnapshotRepository(),
            routineNotifier = FakeRoutineNotifier(),
            routineScheduler = FakeRoutineScheduler(),
            appSettingsDataStore = FakeAppSettingsDataStore(),
            notificationAvailabilityChecker = FakeNotificationAvailabilityChecker(),
            clock = clock,
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
}
