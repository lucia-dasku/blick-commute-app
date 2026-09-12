package se.blick.app.notification

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * A direct test of the actual disk-backed persistence [se.blick.app.scheduling.NotificationRecoveryCoordinator]
 * relies on for surviving process recreation — see [RecoveryPendingStateStore]'s own doc for why
 * this must be durable rather than an in-memory field. Each [PreferencesRecoveryPendingStateStore]
 * instance below is backed by its own freshly-created [androidx.datastore.core.DataStore]. Each
 * test gets a unique temporary file, while all three instances in the recreation test point at
 * that test's SAME file (via [PreferenceDataStoreFactory.create], not the cached `by
 * preferencesDataStore()` property delegate `DataStoreModule` uses in production, which memoizes
 * one instance per file name for the whole process). Each instance is given its OWN
 * [CoroutineScope] and job, cancelled and joined before the next instance opens the same file —
 * DataStore itself refuses to have two instances simultaneously active against one file
 * (`IllegalStateException`), so this scope hand-off is what actually simulates "the process was
 * killed and a fresh one reopened the same file", rather than two live instances racing each
 * other. Same pattern as the deleted `PreferencesNotificationAvailabilityStateStoreTest` this
 * store replaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreferencesRecoveryPendingStateStoreTest {

    private fun uniqueStoreFile(): File =
        Files.createTempDirectory("test_recovery_pending_")
            .resolve("state.preferences_pb")
            .toFile()

    private fun deleteStoreFile(storeFile: File) {
        val storeDirectory = checkNotNull(storeFile.parentFile)
        Files.deleteIfExists(storeFile.toPath())
        Files.deleteIfExists(storeDirectory.toPath())
    }

    private fun newStore(
        scope: CoroutineScope,
        storeFile: File,
    ): PreferencesRecoveryPendingStateStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile },
        )
        return PreferencesRecoveryPendingStateStore(dataStore)
    }

    @Test
    fun `recoveryPending defaults to false before anything has ever been recorded`() = runTest {
        val storeFile = uniqueStoreFile()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(Dispatchers.IO + storeJob)
        try {
            assertEquals(false, newStore(storeScope, storeFile).recoveryPending.first())
        } finally {
            storeJob.cancelAndJoin()
            deleteStoreFile(storeFile)
        }
    }

    @Test
    fun `a pending flag survives a fresh store instance backed by the same file, simulating process recreation`() =
        runTest {
            val storeFile = uniqueStoreFile()
            try {
                val firstJob = SupervisorJob()
                val firstScope = CoroutineScope(Dispatchers.IO + firstJob)
                try {
                    // markRecoveryPending() suspends until durably persisted, so it is safe to
                    // stop this scope immediately afterward -- the write is already complete.
                    newStore(firstScope, storeFile).markRecoveryPending()
                } finally {
                    firstJob.cancelAndJoin()
                }

                val secondJob = SupervisorJob()
                val secondScope = CoroutineScope(Dispatchers.IO + secondJob)
                try {
                    val afterRecreation = newStore(secondScope, storeFile)
                    assertEquals(true, afterRecreation.recoveryPending.first())
                    afterRecreation.clearRecoveryPending()
                } finally {
                    secondJob.cancelAndJoin()
                }

                val thirdJob = SupervisorJob()
                val thirdScope = CoroutineScope(Dispatchers.IO + thirdJob)
                try {
                    assertEquals(false, newStore(thirdScope, storeFile).recoveryPending.first())
                } finally {
                    thirdJob.cancelAndJoin()
                }
            } finally {
                deleteStoreFile(storeFile)
            }
        }
}
