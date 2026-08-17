package se.blick.app.data.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Instrumented (requires a device/emulator) rather than a Robolectric JVM test, since
 * that is the more standard way to validate real SQLite behavior via Room, per the
 * requirement for "meaningful Room persistence tests" rather than testing the interface
 * shape alone.
 */
@RunWith(AndroidJUnit4::class)
class RoutineDaoTest {

    private lateinit var db: BlickDatabase
    private lateinit var dao: RoutineDao

    private fun sampleEntity(id: String = "r1") = RoutineEntity(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = "METRO",
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDaysMask = 0b0011111, // Mon-Fri
        startTimeMinutes = 7 * 60 + 30,
        endTimeMinutes = 8 * 60,
        enabled = true,
        pausedDateEpochDay = null,
    )

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BlickDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.routineDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadBack() = runBlocking {
        dao.upsert(sampleEntity())

        val all = dao.observeAll().first()

        assertEquals(1, all.size)
        assertEquals("Morning commute", all.first().name)
    }

    @Test
    fun observeAllReflectsInsertsInNameOrder() = runBlocking {
        dao.upsert(sampleEntity(id = "b").copy(name = "Zebra"))
        dao.upsert(sampleEntity(id = "a").copy(name = "Alpha"))

        val all = dao.observeAll().first()

        assertEquals(listOf("Alpha", "Zebra"), all.map { it.name })
    }

    @Test
    fun updateChangesPersistedRow() = runBlocking {
        dao.upsert(sampleEntity())
        val stored = dao.getById("r1")!!

        dao.update(stored.copy(enabled = false, name = "Renamed"))

        val updated = dao.getById("r1")!!
        assertEquals(false, updated.enabled)
        assertEquals("Renamed", updated.name)
    }

    @Test
    fun deleteByIdRemovesTheRow() = runBlocking {
        dao.upsert(sampleEntity())

        dao.deleteById("r1")

        assertNull(dao.getById("r1"))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun supportsMultipleIndependentRoutinesSimultaneously() = runBlocking {
        dao.upsert(sampleEntity(id = "r1"))
        dao.upsert(sampleEntity(id = "r2").copy(name = "Evening commute", siteId = 9192, siteName = "Slussen"))

        val all = dao.observeAll().first()

        assertEquals(2, all.size)
        assertTrue(all.any { it.siteName == "Fruängen" })
        assertTrue(all.any { it.siteName == "Slussen" })
    }

    @Test
    fun pausedDateRoundTripsThroughEpochDay() = runBlocking {
        dao.upsert(sampleEntity().copy(pausedDateEpochDay = 20000L))

        val stored = dao.getById("r1")!!

        assertEquals(20000L, stored.pausedDateEpochDay)
        assertEquals(LocalDate.ofEpochDay(20000L), stored.toDomain().pausedDate)
    }

    @Test
    fun labelPersistsAfterDatabaseReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "routine-label-reload"
        context.deleteDatabase(databaseName)

        try {
            val firstOpen = Room.databaseBuilder(context, BlickDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            try {
                firstOpen.routineDao().upsert(sampleEntity().copy(label = "STUDY"))
            } finally {
                firstOpen.close()
            }

            val reopened = Room.databaseBuilder(context, BlickDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals("STUDY", reopened.routineDao().getById("r1")?.label)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
