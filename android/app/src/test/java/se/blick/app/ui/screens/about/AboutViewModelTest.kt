package se.blick.app.ui.screens.about

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.widget.RoutineWidgetUpdater
import java.time.Instant

/**
 * [AppCompatDelegate] is a real AndroidX class, not an interface Blick owns — asserted against
 * directly via Robolectric (real per-app-locale behavior, same convention
 * `RoutineNotificationBuilderTest`/`AndroidRoutineNotifierTest` already use: "the real
 * constructed X, not a hand-rolled copy") rather than mocked, which this codebase does not do
 * for Android framework/AndroidX classes anywhere else.
 *
 * `@Config(sdk = [26])` — see [se.blick.app.locale.AppLocaleTest]'s own doc for why: Robolectric
 * does not fully shadow the framework `LocaleManager` `AppCompatDelegate` delegates to on API
 * 33+ in this test environment, so `setApplicationLocales` does not round-trip through
 * `getApplicationLocales` there; 26 exercises AppCompat's own real pre-33 compat storage
 * instead.
 */
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

    /** Same hand-written-fake convention `WidgetReconcileWorkerTest`'s own `RecordingWidgetUpdater`
     * uses, rather than a mocking library, for this simple an interface. Overrides ONLY
     * [refreshPresentation] -- every other [RoutineWidgetUpdater] member keeps the interface's
     * own default/no-op behavior, since [AboutViewModel] never calls them. */
    private class RecordingWidgetUpdater : RoutineWidgetUpdater {
        var refreshPresentationCallCount = 0
        override suspend fun updateWithDepartures(routine: CommuteRoutine, departuresState: LiveDeparturesState, now: Instant) {}
        override suspend fun clear() {}
        override suspend fun reconcile() {}
        override suspend fun showNotificationsUnavailable(routine: CommuteRoutine) {}
        override suspend fun refreshPresentation() {
            refreshPresentationCallCount++
        }
    }

    @Test
    fun `onLanguageSelected sv sets the AppCompatDelegate application locale to Swedish`() {
        val viewModel = AboutViewModel(RecordingWidgetUpdater())

        viewModel.onLanguageSelected("sv")

        assertEquals("sv", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun `onLanguageSelected en sets the AppCompatDelegate application locale to English`() {
        val viewModel = AboutViewModel(RecordingWidgetUpdater())

        viewModel.onLanguageSelected("en")

        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun `onLanguageSelected triggers exactly one widget presentation refresh`() {
        val widgetUpdater = RecordingWidgetUpdater()
        val viewModel = AboutViewModel(widgetUpdater)

        viewModel.onLanguageSelected("sv")
        dispatcher.scheduler.runCurrent()

        assertEquals(1, widgetUpdater.refreshPresentationCallCount)
    }

    @Test
    fun `switching language twice sets the locale to the latest choice each time`() {
        val viewModel = AboutViewModel(RecordingWidgetUpdater())

        viewModel.onLanguageSelected("sv")
        assertEquals("sv", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        viewModel.onLanguageSelected("en")
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
}
