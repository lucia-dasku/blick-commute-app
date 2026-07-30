package se.blick.app.data.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.data.repository.RoomStaleSnapshotRepository
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.Instant

/**
 * Instrumented (requires a device/emulator), matching [RoutineDaoTest]'s own convention —
 * validates real SQLite behavior via Room, including the entity's `routines.id` foreign key
 * with `ON DELETE CASCADE`. Also exercises [RoomStaleSnapshotRepository] directly against the
 * same in-memory database, since its identity-mismatch guard (see
 * [se.blick.app.data.repository.StaleSnapshotRepository.get]'s own doc) is real logic, not
 * just a DAO passthrough.
 */
@RunWith(AndroidJUnit4::class)
class StaleSnapshotDaoTest {

    private lateinit var db: BlickDatabase
    private lateinit var routineDao: RoutineDao
    private lateinit var dao: StaleSnapshotDao
    private lateinit var repository: RoomStaleSnapshotRepository

    private fun sampleRoutineEntity(id: String = "r1") = RoutineEntity(
        id = id,
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = "METRO",
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDaysMask = 0b0011111,
        startTimeMinutes = 7 * 60,
        endTimeMinutes = 9 * 60,
        enabled = true,
        pausedDateEpochDay = null,
    )

    private val identity = DepartureIdentity(siteId = 9145, lineId = 14, directionCode = 1, transportMode = TransportMode.METRO)

    private fun sampleSnapshot(departureId: String = "d1") = LiveDeparturesSnapshot(
        departures = listOf(
            PreparedDeparture(
                departureId = departureId,
                lineDesignation = "14",
                direction = "T-Centralen",
                destination = "T-Centralen",
                scheduledTime = Instant.parse("2026-07-30T18:00:00Z"),
                expectedTime = Instant.parse("2026-07-30T18:01:00Z"),
                effectiveTime = Instant.parse("2026-07-30T18:01:00Z"),
                minutesRemaining = 4,
                isRealTime = true,
                isCancelled = false,
                state = "EXPECTED",
                journeyState = "EXPECTED",
                predictionState = null,
                tripDeviations = emptyList(),
            ),
        ),
        fetchedAt = Instant.parse("2026-07-30T17:57:00Z"),
    )

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BlickDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        routineDao = db.routineDao()
        dao = db.staleSnapshotDao()
        repository = RoomStaleSnapshotRepository(dao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadBackRoundTripsThroughTheDao() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        dao.upsert(toStaleSnapshotEntity("r1", identity, sampleSnapshot()))

        val stored = dao.getByRoutineId("r1")!!

        assertEquals(9145L, stored.siteId)
        assertEquals(identity, stored.identity())
        assertEquals(listOf("d1"), stored.toSnapshot().departures.map { it.departureId })
    }

    @Test
    fun upsertReplacesThePreviousRowRatherThanFailing() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        dao.upsert(toStaleSnapshotEntity("r1", identity, sampleSnapshot("first")))
        dao.upsert(toStaleSnapshotEntity("r1", identity, sampleSnapshot("second")))

        val stored = dao.getByRoutineId("r1")!!

        assertEquals(listOf("second"), stored.toSnapshot().departures.map { it.departureId })
    }

    @Test
    fun deleteByRoutineIdRemovesTheRow() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        dao.upsert(toStaleSnapshotEntity("r1", identity, sampleSnapshot()))

        dao.deleteByRoutineId("r1")

        assertNull(dao.getByRoutineId("r1"))
    }

    @Test
    fun deletingTheOwningRoutineCascadesToItsStaleSnapshot() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        dao.upsert(toStaleSnapshotEntity("r1", identity, sampleSnapshot()))

        routineDao.deleteById("r1")

        assertNull(dao.getByRoutineId("r1"))
    }

    @Test
    fun repositoryGetReturnsNullWhenNothingWasEverSaved() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())

        assertNull(repository.get("r1", identity))
    }

    @Test
    fun repositorySaveThenGetRoundTripsForTheSameIdentity() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        val snapshot = sampleSnapshot()

        repository.save("r1", identity, snapshot)
        val loaded = repository.get("r1", identity)

        assertEquals(snapshot, loaded)
    }

    @Test
    fun repositoryGetReturnsNullWhenTheStoredIdentityNoLongerMatches() = runBlocking {
        // Simulates an edit that changed the routine's site/line/direction/mode -- the
        // persisted snapshot belongs to the OLD identity and must never be offered as a stale
        // fallback for the new one (see StaleSnapshotRepository.get's own doc).
        routineDao.upsert(sampleRoutineEntity())
        repository.save("r1", identity, sampleSnapshot())

        val differentIdentity = identity.copy(siteId = 9192)

        assertNull(repository.get("r1", differentIdentity))
    }

    @Test
    fun repositoryClearRemovesTheStoredSnapshot() = runBlocking {
        routineDao.upsert(sampleRoutineEntity())
        repository.save("r1", identity, sampleSnapshot())

        repository.clear("r1")

        assertNull(repository.get("r1", identity))
    }

    @Test
    fun twoRoutinesPersistIndependently() = runBlocking {
        routineDao.upsert(sampleRoutineEntity(id = "r1"))
        routineDao.upsert(sampleRoutineEntity(id = "r2"))
        val identity2 = identity.copy(siteId = 9192)

        repository.save("r1", identity, sampleSnapshot("a"))
        repository.save("r2", identity2, sampleSnapshot("b"))

        assertEquals(listOf("a"), repository.get("r1", identity)!!.departures.map { it.departureId })
        assertEquals(listOf("b"), repository.get("r2", identity2)!!.departures.map { it.departureId })
        assertTrue(repository.get("r1", identity2) == null)
    }
}
