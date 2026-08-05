package se.blick.app.data.repository

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.local.room.BlickDatabase
import se.blick.app.data.local.room.RoutineEntity

/**
 * A direct test of the actual disk-backed persistence [RoomRoutineOccurrenceRuntimeRepository]
 * relies on — see [se.blick.app.data.local.room.RoutineOccurrenceRuntimeEntity]'s own doc on why
 * a routine's hard-runtime-cap tracking must survive process recreation (and, in production, a
 * device reboot): a worker run in a freshly-recreated process must be able to continue counting
 * from the SAME occurrence's original start rather than being handed a fresh allowance. Mirrors
 * [RoomRoutineWorkOwnershipRepositoryTest]'s own two-database-instance trick for simulating that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomRoutineOccurrenceRuntimeRepositoryTest {

    // Deliberately short -- Robolectric's own per-test sandbox directory name already embeds
    // this class's (long) name and the (also long, descriptive) test method name; combined with
    // an equally long db file name, the full path can exceed Windows' MAX_PATH.
    private val dbName = "t${System.nanoTime()}.db"
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

    /** [se.blick.app.data.local.room.RoutineOccurrenceRuntimeEntity]'s foreign key requires a
     * matching `routines` row to exist first — the exact values here don't matter to any test
     * below, only that the row exists. */
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

    private fun state(
        occurrenceWindowEndEpochMilli: Long = 1_000L,
        monotonicStartElapsedRealtimeMillis: Long = 2_000L,
        bootCountAtStart: Int = 1,
        hardStopEpochMilli: Long = 3_000L,
    ) = RoutineOccurrenceRuntimeState(
        occurrenceWindowEndEpochMilli = occurrenceWindowEndEpochMilli,
        monotonicStartElapsedRealtimeMillis = monotonicStartElapsedRealtimeMillis,
        bootCountAtStart = bootCountAtStart,
        hardStopEpochMilli = hardStopEpochMilli,
    )

    @Test
    fun `get returns null when nothing has ever been saved for this routine`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())

        assertNull(repository.get("r1"))
    }

    @Test
    fun `save then get round-trips every field exactly`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())
        val saved = state(
            occurrenceWindowEndEpochMilli = 111_111L,
            monotonicStartElapsedRealtimeMillis = 222_222L,
            bootCountAtStart = 7,
            hardStopEpochMilli = 333_333L,
        )

        repository.save("r1", saved)

        assertEquals(saved, repository.get("r1"))
    }

    @Test
    fun `saving again for the same routine replaces the previous state`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())

        repository.save("r1", state(occurrenceWindowEndEpochMilli = 1_000L))
        repository.save("r1", state(occurrenceWindowEndEpochMilli = 2_000L))

        assertEquals(2_000L, repository.get("r1")?.occurrenceWindowEndEpochMilli)
    }

    @Test
    fun `clear removes the state, and is a harmless no-op when nothing exists`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())
        repository.save("r1", state())

        repository.clear("r1")

        assertNull(repository.get("r1"))
        repository.clear("r1") // no-op, must not throw
    }

    @Test
    fun `state for one routine does not affect another`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity("r1"))
        db.routineDao().upsert(routineEntity("r2"))
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())

        repository.save("r1", state(occurrenceWindowEndEpochMilli = 1_000L))
        repository.save("r2", state(occurrenceWindowEndEpochMilli = 2_000L))
        repository.clear("r1")

        assertNull(repository.get("r1"))
        assertEquals(2_000L, repository.get("r2")?.occurrenceWindowEndEpochMilli)
    }

    @Test
    fun `runtime state survives a fresh database instance backed by the same file, simulating process recreation`() =
        runTest {
            val firstDb = openDatabase()
            firstDb.routineDao().upsert(routineEntity())
            RoomRoutineOccurrenceRuntimeRepository(firstDb.routineOccurrenceRuntimeDao())
                .save("r1", state(monotonicStartElapsedRealtimeMillis = 999_999L))
            firstDb.close()

            val secondDb = openDatabase() // same dbName -- the same underlying file on disk
            val repository = RoomRoutineOccurrenceRuntimeRepository(secondDb.routineOccurrenceRuntimeDao())

            assertEquals(999_999L, repository.get("r1")?.monotonicStartElapsedRealtimeMillis)
        }

    @Test
    fun `deleting the routine cascades to remove its runtime state`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomRoutineOccurrenceRuntimeRepository(db.routineOccurrenceRuntimeDao())
        repository.save("r1", state())

        db.routineDao().deleteById("r1")

        assertNull(repository.get("r1"))
    }
}
