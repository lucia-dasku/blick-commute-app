package se.blick.app.data.repository

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.local.room.BlickDatabase
import se.blick.app.data.local.room.RoutineEntity
import se.blick.app.data.local.room.StaleSnapshotEntity
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.Instant

/**
 * A direct test of the actual persistence [RoomStaleSnapshotRepository] relies on (see
 * [RoomRoutineWorkOwnershipRepositoryTest]'s own doc for why a sibling test exercises a REAL
 * [androidx.room.RoomDatabase] rather than a fake DAO) — specifically the corrupted-cache
 * handling in [RoomStaleSnapshotRepository.get]: `departuresJson` is stored as a plain JSON
 * string (see `StaleSnapshotMappers.kt`), not validated by Room/SQLite itself, so a row that
 * predates an incompatible app update, or was otherwise corrupted, must never let a
 * [kotlinx.serialization.SerializationException] escape [get] — the stale snapshot is only ever
 * best-effort fallback data, and a broken row must behave exactly like no cache existed.
 *
 * Uses [Room.inMemoryDatabaseBuilder] rather than [RoomRoutineWorkOwnershipRepositoryTest]'s
 * own uniquely-named on-disk file per test: unlike that class's own "survives a fresh database
 * instance backed by the same file" case, nothing here ever closes and reopens a database
 * against the same identity, so there is no on-disk persistence behavior actually under test —
 * only real Room/SQLite query and constraint behavior, which an in-memory database exercises
 * identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomStaleSnapshotRepositoryTest {

    private var openDb: BlickDatabase? = null

    private fun openDatabase(): BlickDatabase {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, BlickDatabase::class.java).build()
        openDb = db
        return db
    }

    @After
    fun tearDown() {
        openDb?.close()
    }

    /** [StaleSnapshotEntity]'s foreign key requires a matching `routines` row to exist first —
     * the exact values here don't matter to any test below, only that the row exists. */
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

    private val identity = DepartureIdentity(siteId = 9145, lineId = 14, directionCode = 1, transportMode = TransportMode.METRO)

    private fun sampleSnapshot(role: JourneyRole? = null) = LiveDeparturesSnapshot(
        departures = listOf(
            PreparedDeparture(
                departureId = "d1",
                lineDesignation = "14",
                direction = "T-Centralen",
                destination = "T-Centralen",
                scheduledTime = Instant.parse("2026-07-27T05:05:00Z"),
                expectedTime = null,
                effectiveTime = Instant.parse("2026-07-27T05:05:00Z"),
                minutesRemaining = 5,
                isRealTime = false,
                isCancelled = false,
                state = "EXPECTED",
                journeyState = "EXPECTED",
                predictionState = null,
                tripDeviations = emptyList(),
                journeyRole = role,
            ),
        ),
        fetchedAt = Instant.parse("2026-07-27T05:00:00Z"),
    )

    /** Inserts a row directly via the DAO with an arbitrary [departuresJson] — bypasses
     * [se.blick.app.data.local.room.toStaleSnapshotEntity]'s own (always-valid) encoding, the
     * only way to simulate a row that has already gone corrupt on disk. */
    private suspend fun insertRawRow(db: BlickDatabase, routineId: String, departuresJson: String) {
        db.staleSnapshotDao().upsert(
            StaleSnapshotEntity(
                routineId = routineId,
                siteId = identity.siteId,
                lineId = identity.lineId,
                directionCode = identity.directionCode,
                transportMode = identity.transportMode.name,
                fetchedAtEpochMilli = Instant.parse("2026-07-27T05:00:00Z").toEpochMilli(),
                departuresJson = departuresJson,
            ),
        )
    }

    @Test
    fun `a valid cached snapshot round-trips through save and get unchanged`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        val snapshot = sampleSnapshot()

        repository.save("r1", identity, snapshot)
        val result = repository.get("r1", identity)

        assertEquals(snapshot, result)
    }

    // ---- journeyRole: backend-authoritative, must survive a stale-snapshot round-trip
    // exactly, never silently dropped or defaulted to PRIMARY (see StaleSnapshotMappers.kt's
    // own doc) ----

    @Test
    fun `a PRIMARY journeyRole round-trips through save and get unchanged`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        val snapshot = sampleSnapshot(role = JourneyRole.PRIMARY)

        repository.save("r1", identity, snapshot)
        val result = repository.get("r1", identity)

        assertEquals(JourneyRole.PRIMARY, result?.departures?.single()?.journeyRole)
    }

    @Test
    fun `a NEXT journeyRole round-trips through save and get unchanged`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        val snapshot = sampleSnapshot(role = JourneyRole.NEXT)

        repository.save("r1", identity, snapshot)
        val result = repository.get("r1", identity)

        assertEquals(JourneyRole.NEXT, result?.departures?.single()?.journeyRole)
    }

    @Test
    fun `an ALTERNATIVE journeyRole round-trips through save and get unchanged`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        val snapshot = sampleSnapshot(role = JourneyRole.ALTERNATIVE)

        repository.save("r1", identity, snapshot)
        val result = repository.get("r1", identity)

        assertEquals(JourneyRole.ALTERNATIVE, result?.departures?.single()?.journeyRole)
    }

    @Test
    fun `a snapshot persisted before journeyRole existed still loads, with a null role rather than failing`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        // No "journeyRole" key at all -- exactly what an app version predating this field
        // would have written. kotlinx.serialization fills the missing key with
        // StaleDepartureRow's own declared default (null) rather than failing to decode.
        val legacyJson = """
            [{"departureId":"d1","lineDesignation":"14","direction":"T-Centralen","destination":"T-Centralen",
            "scheduledTimeEpochMilli":${Instant.parse("2026-07-27T05:05:00Z").toEpochMilli()},"expectedTimeEpochMilli":null,
            "effectiveTimeEpochMilli":${Instant.parse("2026-07-27T05:05:00Z").toEpochMilli()},"minutesRemaining":5,
            "isRealTime":false,"isCancelled":false,"state":"EXPECTED","journeyState":"EXPECTED","predictionState":null}]
        """.trimIndent()
        insertRawRow(db, "r1", legacyJson)
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())

        val result = repository.get("r1", identity)

        assertNotNull("a legacy row (missing journeyRole entirely) must still load, not be treated as corrupted", result)
        assertNull(result?.departures?.single()?.journeyRole)
    }

    @Test
    fun `a malformed stored journeyRole value fails closed to null, never silently becoming PRIMARY`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val corruptRoleJson = """
            [{"departureId":"d1","lineDesignation":"14","direction":"T-Centralen","destination":"T-Centralen",
            "scheduledTimeEpochMilli":${Instant.parse("2026-07-27T05:05:00Z").toEpochMilli()},"expectedTimeEpochMilli":null,
            "effectiveTimeEpochMilli":${Instant.parse("2026-07-27T05:05:00Z").toEpochMilli()},"minutesRemaining":5,
            "isRealTime":false,"isCancelled":false,"state":"EXPECTED","journeyState":"EXPECTED","predictionState":null,
            "journeyRole":"NOT_A_REAL_ROLE"}]
        """.trimIndent()
        insertRawRow(db, "r1", corruptRoleJson)
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())

        val result = repository.get("r1", identity)

        assertNotNull("a malformed role value must not corrupt decoding of the row as a whole", result)
        assertNull(result?.departures?.single()?.journeyRole)
    }

    @Test
    fun `malformed JSON syntax does not escape get as an exception`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        insertRawRow(db, "r1", departuresJson = "{not valid json at all")
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())

        // Merely not throwing is the assertion here -- see the next tests for the specific
        // deletion/null-return behaviour this same call must also produce.
        repository.get("r1", identity)
    }

    @Test
    fun `JSON that is syntactically valid but missing required fields also does not escape get`() = runTest {
        // A different SerializationException subtype (MissingFieldException) than the malformed
        // -syntax case above -- proves the catch is scoped to the exception's public supertype,
        // not one specific corruption shape (see StaleDepartureRow's own required fields).
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        insertRawRow(db, "r1", departuresJson = "[{}]")
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())

        repository.get("r1", identity)
    }

    @Test
    fun `a corrupted snapshot is deleted from the database`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        insertRawRow(db, "r1", departuresJson = "{not valid json at all")
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        assertNotNull("test setup sanity check -- the corrupt row must exist before get() runs", db.staleSnapshotDao().getByRoutineId("r1"))

        repository.get("r1", identity)

        assertNull("the corrupted row must be removed, not left behind to fail again next read", db.staleSnapshotDao().getByRoutineId("r1"))
    }

    @Test
    fun `get returns null for a corrupted snapshot, exactly like a normal cache miss`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        insertRawRow(db, "r1", departuresJson = "{not valid json at all")
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())

        val result = repository.get("r1", identity)

        assertNull(result)
    }

    @Test
    fun `an identity mismatch still returns null without touching the stored row, unchanged from before this fix`() = runTest {
        val db = openDatabase()
        db.routineDao().upsert(routineEntity())
        val repository = RoomStaleSnapshotRepository(db.staleSnapshotDao())
        repository.save("r1", identity, sampleSnapshot())
        val mismatchedIdentity = identity.copy(lineId = 99)

        val result = repository.get("r1", mismatchedIdentity)

        assertNull(result)
        // The identity check happens BEFORE the corruption-handling try/catch touches anything
        // -- a mismatch is not corruption, and must never delete a perfectly valid row that
        // simply belongs to a different (e.g. not-yet-updated) identity.
        assertNotNull(
            "an identity mismatch must not delete the stored row",
            db.staleSnapshotDao().getByRoutineId("r1"),
        )
    }
}
