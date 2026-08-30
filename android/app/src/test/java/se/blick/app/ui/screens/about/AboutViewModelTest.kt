package se.blick.app.ui.screens.about

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.data.local.datastore.AppSettings
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.ui.theme.AppearanceMode
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = android.app.Application::class)
class AboutViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var refreshPresentationCallCount = 0
        override suspend fun updateWithDepartures(
            routine: CommuteRoutine,
            departuresState: LiveDeparturesState,
            now: Instant,
        ) = Unit
        override suspend fun clear() = Unit
        override suspend fun reconcile() = Unit
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) = Unit
        override suspend fun refreshPresentation() { refreshPresentationCallCount++ }
    }

    private class FakeSettingsDataStore(initial: AppSettings = AppSettings()) : AppSettingsDataStore {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setUseDarkTheme(useDarkTheme: Boolean?) {
            state.value = state.value.copy(useDarkTheme = useDarkTheme)
        }
        override suspend fun setUseStockholmNightTheme(enabled: Boolean) {
            state.value = state.value.copy(useStockholmNightTheme = enabled)
        }
        override suspend fun setHasSeenNotificationRationale(seen: Boolean) {
            state.value = state.value.copy(hasSeenNotificationRationale = seen)
        }
        override suspend fun setHasAcknowledgedAttribution(acknowledged: Boolean) {
            state.value = state.value.copy(hasAcknowledgedAttribution = acknowledged)
        }
        fun current(): AppSettings = state.value
    }

    private class FakePremiumRepository(initial: EntitlementState) : PremiumEntitlementRepository {
        private val state = MutableStateFlow(initial)
        override val entitlement: StateFlow<EntitlementState> = state
        override val localizedPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun refresh() = Unit
        override suspend fun restore() = Unit
        override fun launchPurchase(activity: Activity) = Unit
    }

    private class FakeNotificationChecker(
        var current: NotificationAvailability = NotificationAvailability.Available,
    ) : NotificationAvailabilityChecker {
        override fun check(): NotificationAvailability = current
    }

    private class FakePromotedNotificationChecker(var current: Boolean = false) : PromotedNotificationChecker {
        override fun isPromotable(): Boolean = current
    }

    private fun viewModel(
        widgetUpdater: RecordingWidgetUpdater = RecordingWidgetUpdater(),
        settings: FakeSettingsDataStore = FakeSettingsDataStore(),
        entitlement: EntitlementState = EntitlementState.Free,
        checker: FakeNotificationChecker = FakeNotificationChecker(),
        promotedChecker: FakePromotedNotificationChecker = FakePromotedNotificationChecker(),
    ) = AboutViewModel(
        routineWidgetUpdater = widgetUpdater,
        appSettingsDataStore = settings,
        premiumEntitlementRepository = FakePremiumRepository(entitlement),
        notificationAvailabilityChecker = checker,
        promotedNotificationChecker = promotedChecker,
    )

    @Test
    fun `language selection still sets AppCompat application locale`() {
        val viewModel = viewModel()

        viewModel.onLanguageSelected("sv")
        assertEquals("sv", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        viewModel.onLanguageSelected("en")
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun `language selection refreshes widget presentation exactly once`() {
        val updater = RecordingWidgetUpdater()
        val viewModel = viewModel(widgetUpdater = updater)

        viewModel.onLanguageSelected("sv")
        dispatcher.scheduler.runCurrent()

        assertEquals(1, updater.refreshPresentationCallCount)
    }

    @Test
    fun `appearance selection writes the existing nullable dark theme preference`() = runTest(dispatcher) {
        val settings = FakeSettingsDataStore()
        val viewModel = viewModel(settings = settings)

        viewModel.onAppearanceSelected(AppearanceMode.Dark)
        dispatcher.scheduler.runCurrent()
        assertEquals(true, settings.current().useDarkTheme)

        viewModel.onAppearanceSelected(AppearanceMode.Light)
        dispatcher.scheduler.runCurrent()
        assertEquals(false, settings.current().useDarkTheme)

        viewModel.onAppearanceSelected(AppearanceMode.System)
        dispatcher.scheduler.runCurrent()
        assertEquals(null, settings.current().useDarkTheme)
    }

    @Test
    fun `appearance selection refreshes widget presentation exactly once`() = runTest(dispatcher) {
        val updater = RecordingWidgetUpdater()
        val viewModel = viewModel(widgetUpdater = updater)

        viewModel.onAppearanceSelected(AppearanceMode.Dark)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, updater.refreshPresentationCallCount)
    }

    @Test
    fun `Premium can select Stockholm night without overwriting the regular fallback`() = runTest(dispatcher) {
        val settings = FakeSettingsDataStore(AppSettings(useDarkTheme = false))
        val viewModel = viewModel(settings = settings, entitlement = EntitlementState.Premium)

        viewModel.onAppearanceSelected(AppearanceMode.StockholmNight)
        dispatcher.scheduler.runCurrent()

        assertEquals(true, settings.current().useStockholmNightTheme)
        assertEquals(false, settings.current().useDarkTheme)
    }

    @Test
    fun `Free cannot activate Stockholm night`() = runTest(dispatcher) {
        val settings = FakeSettingsDataStore(AppSettings(useDarkTheme = null))
        val viewModel = viewModel(settings = settings, entitlement = EntitlementState.Free)

        viewModel.onAppearanceSelected(AppearanceMode.StockholmNight)
        dispatcher.scheduler.runCurrent()

        assertEquals(false, settings.current().useStockholmNightTheme)
    }

    @Test
    fun `saved Stockholm night is shown only while Premium access is available`() = runTest(dispatcher) {
        val stored = FakeSettingsDataStore(
            AppSettings(useDarkTheme = false, useStockholmNightTheme = true),
        )

        val premiumViewModel = viewModel(settings = stored, entitlement = EntitlementState.Premium)
        dispatcher.scheduler.runCurrent()
        assertEquals(AppearanceMode.StockholmNight, premiumViewModel.uiState.value.appearanceMode)

        val freeViewModel = viewModel(settings = stored, entitlement = EntitlementState.Free)
        dispatcher.scheduler.runCurrent()
        assertEquals(AppearanceMode.Light, freeViewModel.uiState.value.appearanceMode)
    }

    @Test
    fun `selecting a regular appearance disables Stockholm night`() = runTest(dispatcher) {
        val settings = FakeSettingsDataStore(
            AppSettings(useDarkTheme = true, useStockholmNightTheme = true),
        )
        val viewModel = viewModel(settings = settings, entitlement = EntitlementState.Premium)

        viewModel.onAppearanceSelected(AppearanceMode.Light)
        dispatcher.scheduler.runCurrent()

        assertEquals(false, settings.current().useStockholmNightTheme)
        assertEquals(false, settings.current().useDarkTheme)
    }

    @Test
    fun `premium display state is sourced from entitlement repository`() {
        assertEquals(EntitlementState.Premium, viewModel(entitlement = EntitlementState.Premium).uiState.value.entitlement)
        assertEquals(EntitlementState.Free, viewModel(entitlement = EntitlementState.Free).uiState.value.entitlement)
    }

    @Test
    fun `notification state is sourced from checker and refreshes after system settings`() {
        val checker = FakeNotificationChecker(NotificationAvailability.PermissionMissing)
        val viewModel = viewModel(checker = checker)
        assertEquals(NotificationAvailability.PermissionMissing, viewModel.uiState.value.notificationAvailability)

        checker.current = NotificationAvailability.ChannelDisabled
        viewModel.refreshNotificationAvailability()
        dispatcher.scheduler.runCurrent()

        assertEquals(NotificationAvailability.ChannelDisabled, viewModel.uiState.value.notificationAvailability)
    }

    @Test
    fun `Live Updates state is sourced from promoted notification checker and refreshes after system settings`() {
        val checker = FakePromotedNotificationChecker(current = true)
        val viewModel = viewModel(promotedChecker = checker)
        assertEquals(true, viewModel.uiState.value.liveUpdatesEnabled)

        checker.current = false
        viewModel.refreshNotificationAvailability()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.liveUpdatesEnabled)
    }
}
