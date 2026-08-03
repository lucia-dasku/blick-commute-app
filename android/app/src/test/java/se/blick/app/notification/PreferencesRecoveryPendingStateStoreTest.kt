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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A direct test of the actual disk-backed persistence [se.blick.app.scheduling.NotificationRecoveryCoordinator]
 * relies on for surviving process recreation — see [RecoveryPendingStateStore]'s own doc for why
 * this must be durable rather than an in-memory field. Each [PreferencesRecoveryPendingStateStore]
 * instance below is backed by its own freshly-created [androidx.datastore.core.DataStore] pointed
 * at the SAME file (via [PreferenceDataStoreFactory.create], not the cached `by
 * preferencesDataStore()` property delegate `DataStoreModule` uses in production, which memoizes
 * one instance per file name for the whole process). Each instance is given its OWN
 * [CoroutineScope], cancelled before the next instance opens the same file — DataStore itself
 * refuses to have two instances simultaneously active against one file (`IllegalStateException`),
 * so this scope hand-off is what actually simulates "the process was killed and a fresh one
 * reopened the same file", rather than two live instances racing each other. Same pattern as the
 * deleted `PreferencesNotificationAvailabilityStateStoreTest` this store replaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreferencesRecoveryPendingStateStoreTest {

    private fun newStore(scope: CoroutineScope): PreferencesRecoveryPendingStateStore {
        val context = RuntimeEnvironment.getApplication()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("test_recovery_pending") },
        )
        return PreferencesRecoveryPendingStateStore(dataStore)
    }

    @Test
    fun `recoveryPending defaults to false before anything has ever been recorded`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            assertEquals(false, newStore(scope).recoveryPending.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a pending flag survives a fresh store instance backed by the same file, simulating process recreation`() =
        runTest {
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                // markRecoveryPending() suspends until durably persisted, so it is safe to
                // cancel this scope immediately afterward -- the write is already complete.
                newStore(firstScope).markRecoveryPending()
            } finally {
                firstScope.cancel()
            }

            val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val afterRecreation = newStore(secondScope)
                assertEquals(true, afterRecreation.recoveryPending.first())
                afterRecreation.clearRecoveryPending()
            } finally {
                secondScope.cancel()
            }

            val thirdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                assertEquals(false, newStore(thirdScope).recoveryPending.first())
            } finally {
                thirdScope.cancel()
            }
        }
}
