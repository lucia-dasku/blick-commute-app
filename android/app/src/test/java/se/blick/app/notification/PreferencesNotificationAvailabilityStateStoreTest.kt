package se.blick.app.notification

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A direct test of the actual disk-backed persistence [ForegroundNotificationRecovery] relies
 * on — see [NotificationAvailabilityStateStore]'s own doc on why an in-memory field cannot
 * substitute for this. Each [PreferencesNotificationAvailabilityStateStore] instance below is
 * backed by its own freshly-created [androidx.datastore.core.DataStore] pointed at the SAME
 * file (via [PreferenceDataStoreFactory.create], not the cached `by preferencesDataStore()`
 * property delegate `DataStoreModule` uses in production, which memoizes one instance per file
 * name for the whole process). Each instance is given its OWN [CoroutineScope], cancelled
 * before the next instance opens the same file — DataStore itself refuses to have two
 * instances simultaneously active against one file (`IllegalStateException`), so this scope
 * hand-off is what actually simulates "the process was killed and a fresh one reopened the
 * same file", rather than two live instances racing each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreferencesNotificationAvailabilityStateStoreTest {

    private fun newStore(scope: CoroutineScope): PreferencesNotificationAvailabilityStateStore {
        val context = RuntimeEnvironment.getApplication()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("test_notification_availability") },
        )
        return PreferencesNotificationAvailabilityStateStore(dataStore)
    }

    @Test
    fun `lastKnownAvailable is null before anything has ever been recorded`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            assertNull(newStore(scope).lastKnownAvailable.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a written value survives a fresh store instance backed by the same file, simulating process recreation`() = runTest {
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            // edit {} suspends until the new value is durably persisted, so it is safe to
            // cancel this scope immediately afterward -- the write is already complete.
            newStore(firstScope).setLastKnownAvailable(false)
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val afterRecreation = newStore(secondScope)
            assertEquals(false, afterRecreation.lastKnownAvailable.first())
            afterRecreation.setLastKnownAvailable(true)
        } finally {
            secondScope.cancel()
        }

        val thirdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            assertEquals(true, newStore(thirdScope).lastKnownAvailable.first())
        } finally {
            thirdScope.cancel()
        }
    }
}
