package se.blick.app.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreferencesAppSettingsDataStoreTest {

    @Test
    fun `appearance preference persists System Light and Dark through existing DataStore`() = runTest {
        val file = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "appearance-${System.nanoTime()}.preferences_pb",
        )
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val settings = PreferencesAppSettingsDataStore(dataStore)

        settings.setUseDarkTheme(true)
        assertEquals(true, settings.settings.first().useDarkTheme)

        settings.setUseDarkTheme(false)
        assertEquals(false, settings.settings.first().useDarkTheme)

        settings.setUseDarkTheme(null)
        assertEquals(null, settings.settings.first().useDarkTheme)
    }

    @Test
    fun `appearance preference survives a fresh DataStore instance`() = runTest {
        val file = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "appearance-restart-${System.nanoTime()}.preferences_pb",
        )
        val firstScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val firstSettings = PreferencesAppSettingsDataStore(
            PreferenceDataStoreFactory.create(scope = firstScope) { file },
        )
        firstSettings.setUseDarkTheme(true)
        firstScope.cancel()
        testScheduler.advanceUntilIdle()

        val secondScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val restoredSettings = PreferencesAppSettingsDataStore(
                PreferenceDataStoreFactory.create(scope = secondScope) { file },
            )
            assertEquals(true, restoredSettings.settings.first().useDarkTheme)
        } finally {
            secondScope.cancel()
        }
    }
}
