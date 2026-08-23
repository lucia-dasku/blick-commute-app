package se.blick.app.ui.screens.routinelist

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.domain.model.TransportMode
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.PremiumRoutineOrderStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleRoutine(id: String = "r1", name: String = "Morning commute") = CommuteRoutine(
        id = id,
        name = name,
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        startTime = LocalTime.of(7, 30),
        endTime = LocalTime.of(8, 0),
    )

    private class FakeRoutineRepository(initial: List<CommuteRoutine>) : RoutineRepository {
        private val state = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<CommuteRoutine>> = state

        override suspend fun getById(id: String): CommuteRoutine? = state.value.find { it.id == id }

        override suspend fun save(routine: CommuteRoutine) {
            state.value = state.value.filterNot { it.id == routine.id } + routine
        }

        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun pauseForDate(id: String, date: LocalDate) {
            state.value = state.value.map { if (it.id == id) it.copy(pausedDate = date) else it }
        }

        override suspend fun clearPause(id: String) {
            state.value = state.value.map { if (it.id == id) it.copy(pausedDate = null) else it }
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override suspend fun hasAnyRoutine(): Boolean = state.value.isNotEmpty()
    }

    private class FakeEntitlementRepository(initial: EntitlementState) : PremiumEntitlementRepository {
        private val state = MutableStateFlow(initial)
        override val entitlement = state
        override val localizedPrice = MutableStateFlow<String?>(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }

    private class FakePremiumRoutineOrderStore(initial: List<String> = emptyList()) : PremiumRoutineOrderStore {
        private val state = MutableStateFlow(initial)
        override val orderedRoutineIds = state
        val savedOrders = mutableListOf<List<String>>()

        override fun saveOrder(routineIds: List<String>) {
            savedOrders += routineIds
            state.value = routineIds
        }
    }

    @Test
    fun `starts in loading state before the repository emits`() = runTest(dispatcher) {
        val repository = FakeRoutineRepository(listOf(sampleRoutine()))
        val viewModel = RoutineListViewModel(repository)

        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `reflects routines from the repository once collected`() = runTest(dispatcher) {
        val routine = sampleRoutine()
        val repository = FakeRoutineRepository(listOf(routine))
        val viewModel = RoutineListViewModel(repository)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(listOf(routine), viewModel.uiState.value.routines)
    }

    @Test
    fun `orders all assigned labels by product priority`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("other").copy(label = RoutineLabel.OTHER),
            sampleRoutine("gym").copy(label = RoutineLabel.GYM),
            sampleRoutine("work").copy(label = RoutineLabel.WORK),
            sampleRoutine("hobby").copy(label = RoutineLabel.HOBBY),
            sampleRoutine("school").copy(label = RoutineLabel.STUDY),
            sampleRoutine("home").copy(label = RoutineLabel.HOME),
        )
        val viewModel = RoutineListViewModel(FakeRoutineRepository(routines))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("home", "school", "work", "gym", "hobby", "other"),
            viewModel.uiState.value.routines.map { it.id },
        )
    }

    @Test
    fun `orders only the sparse label groups that are present`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("hobby").copy(label = RoutineLabel.HOBBY),
            sampleRoutine("school").copy(label = RoutineLabel.STUDY),
        )
        val viewModel = RoutineListViewModel(FakeRoutineRepository(routines))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("school", "hobby"), viewModel.uiState.value.routines.map { it.id })
    }

    @Test
    fun `orders work gym and other without creating missing groups`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("other").copy(label = RoutineLabel.OTHER),
            sampleRoutine("gym").copy(label = RoutineLabel.GYM),
            sampleRoutine("work").copy(label = RoutineLabel.WORK),
        )
        val viewModel = RoutineListViewModel(FakeRoutineRepository(routines))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("work", "gym", "other"), viewModel.uiState.value.routines.map { it.id })
    }

    @Test
    fun `preserves repository order within the same label and places unlabeled routines last`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("work-first", "Alpha").copy(label = RoutineLabel.WORK),
            sampleRoutine("unlabeled", "No label"),
            sampleRoutine("work-second", "Beta").copy(label = RoutineLabel.WORK),
            sampleRoutine("home", "Home").copy(label = RoutineLabel.HOME),
        )
        val viewModel = RoutineListViewModel(FakeRoutineRepository(routines))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("home", "work-first", "work-second", "unlabeled"),
            viewModel.uiState.value.routines.map { it.id },
        )
    }

    @Test
    fun `premium applies the saved custom order after the default order is established`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("work").copy(label = RoutineLabel.WORK),
            sampleRoutine("home").copy(label = RoutineLabel.HOME),
            sampleRoutine("gym").copy(label = RoutineLabel.GYM),
        )
        val viewModel = RoutineListViewModel(
            routineRepository = FakeRoutineRepository(routines),
            entitlementRepository = FakeEntitlementRepository(EntitlementState.Premium),
            premiumRoutineOrderStore = FakePremiumRoutineOrderStore(listOf("gym", "home", "work")),
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("gym", "home", "work"), viewModel.uiState.value.routines.map { it.id })
    }

    @Test
    fun `free ignores a saved custom order and uses label priority`() = runTest(dispatcher) {
        val routines = listOf(
            sampleRoutine("work").copy(label = RoutineLabel.WORK),
            sampleRoutine("home").copy(label = RoutineLabel.HOME),
            sampleRoutine("gym").copy(label = RoutineLabel.GYM),
        )
        val viewModel = RoutineListViewModel(
            routineRepository = FakeRoutineRepository(routines),
            entitlementRepository = FakeEntitlementRepository(EntitlementState.Free),
            premiumRoutineOrderStore = FakePremiumRoutineOrderStore(listOf("gym", "work", "home")),
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("home", "work", "gym"), viewModel.uiState.value.routines.map { it.id })
    }

    @Test
    fun `premium move persists all currently visible routine ids in the new order`() = runTest(dispatcher) {
        val orderStore = FakePremiumRoutineOrderStore()
        val viewModel = RoutineListViewModel(
            routineRepository = FakeRoutineRepository(
                listOf(
                    sampleRoutine("home").copy(label = RoutineLabel.HOME),
                    sampleRoutine("work").copy(label = RoutineLabel.WORK),
                    sampleRoutine("gym").copy(label = RoutineLabel.GYM),
                ),
            ),
            entitlementRepository = FakeEntitlementRepository(EntitlementState.Premium),
            premiumRoutineOrderStore = orderStore,
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.moveRoutine("gym", "home")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("gym", "home", "work"), orderStore.savedOrders.single())
        assertEquals(listOf("gym", "home", "work"), viewModel.uiState.value.routines.map { it.id })
    }

    @Test
    fun `free move is ignored and does not overwrite the dormant premium order`() = runTest(dispatcher) {
        val orderStore = FakePremiumRoutineOrderStore(listOf("work", "home"))
        val viewModel = RoutineListViewModel(
            routineRepository = FakeRoutineRepository(
                listOf(
                    sampleRoutine("home").copy(label = RoutineLabel.HOME),
                    sampleRoutine("work").copy(label = RoutineLabel.WORK),
                ),
            ),
            entitlementRepository = FakeEntitlementRepository(EntitlementState.Free),
            premiumRoutineOrderStore = orderStore,
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.moveRoutine("work", "home")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(orderStore.savedOrders.isEmpty())
        assertEquals(listOf("home", "work"), viewModel.uiState.value.routines.map { it.id })
    }
}
