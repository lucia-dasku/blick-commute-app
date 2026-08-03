package se.blick.app.data.repository

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.local.room.BlickDatabase
import se.blick.app.data.local.room.RoutineEntity

/**
 * A direct test of the actual disk-backed persistence [RoomRoutineWorkOwnershipRepository]
 * relies on — see [se.blick.app.data.local.room.RoutineWorkOwnershipEntity]'s own doc on why
 * content ownership must survive process recreation: a worker run in a freshly-recreated
 * process must be able to tell whether an earlier process's now-dead run still "owns" the
 * content it's looking at. Unlike the in-memory [RoutineActiveWindowWorker]-level ownership
 * tests in `RoutineActiveWindowWorkerTest` (which use a fake repository to isolate the worker's
 * own cleanup-gating logic), this exercises a REAL, file-backed [androidx.room.RoomDatabase] —
 * each test gets its own uniquely-named database file, and the "survives recreation" test below
 * closes one [BlickDatabase] instance and opens a fresh one against the SAME file, mirroring
 * [se.blick.app.notification.PreferencesRecoveryPendingStateStoreTest]'s identical two-instance
 * trick for DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomRoutineWorkOwnershipRepositoryTest {

    private val dbName = "test-ownership-${System.nanoTime()}.db"
    private var openDb: BlickDatabase? = null

    private fun openDatabase(): BlickDatabase {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.databaseBuilder(context, BlickDatabase::class.java, dbName).build()
        openDb = db
        return db
    }

    @After
    fun tearDown() {
        openDb?.close()
    }

    /** [RoutineWorkOwnershipEntity][se.blick.app.data.local.room.RoutineWorkOwnershipEntity]'s
     * foreign key requires a matching `routines` row to exist first — the exact values here
     * don't matter to any test below, only that the row exists. */
    private fun routineEntity(id: String = "r1") = RoutineEntity(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = "METRO",
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDaysMask = 0b0000001,
        startTimeMinutes = 420,
        endTimeMinutes = 540,
        enabled = true,
        pausedDateEpochDay = null,
    )

    @Test
    fun `isOwner is false when nothing has ever been claimed for this routine`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineWorkOwnershipRepository(db.routineWorkOwnershipDao())

        assertEquals(false, repository.isOwner("r1", "some-work-id"))
    }

    @Test
    fun `claiming ownership makes isOwner true only for the claiming work id`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineWorkOwnershipRepository(db.routineWorkOwnershipDao())

        repository.claim("r1", "work-a")

        assertEquals(true, repository.isOwner("r1", "work-a"))
        assertEquals(false, repository.isOwner("r1", "work-b"))
    }

    @Test
    fun `claiming again for a replacement run invalidates the previous owner`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineWorkOwnershipRepository(db.routineWorkOwnershipDao())

        repository.claim("r1", "work-a")
        // e.g. editing an active routine cancels the old worker and immediately starts a new
        // one, which claims ownership for itself under its own (different) WorkManager id.
        repository.claim("r1", "work-b")

        assertEquals(
            "the superseded run must no longer be recognized as the owner",
            false,
            repository.isOwner("r1", "work-a"),
        )
        assertEquals(true, repository.isOwner("r1", "work-b"))
    }

    @Test
    fun `ownership survives a fresh database instance backed by the same file, simulating process recreation`() =
        runTest {
            val firstDb = openDatabase()
            firstDb.routineDao().upsert(routineEntity())
            RoomRoutineWorkOwnershipRepository(firstDb.routineWorkOwnershipDao()).claim("r1", "work-a")
            firstDb.close()

            val secondDb = openDatabase() // same dbName -- the same underlying file on disk
            val repository = RoomRoutineWorkOwnershipRepository(secondDb.routineWorkOwnershipDao())

            assertEquals(true, repository.isOwner("r1", "work-a"))
        }
}
