package se.blick.app.data.repository

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.blick.app.data.local.room.BlickDatabase
import se.blick.app.domain.model.ActiveCommuteSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomActiveCommuteOwnershipRepositoryTest {
    private val dbName = "test-active-ownership-${System.nanoTime()}.db"
    private var openDb: BlickDatabase? = null

    private fun openDatabase(): BlickDatabase =
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), BlickDatabase::class.java, dbName)
            .build()
            .also { openDb = it }

    private fun openInMemoryDatabase(): BlickDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BlickDatabase::class.java)
            .build()
            .also { openDb = it }

    @After
    fun tearDown() {
        openDb?.close()
    }

    @Test
    fun `routine claim is durable across database recreation`() = runTest {
        val first = openDatabase()
        RoomActiveCommuteOwnershipRepository(first.activeCommuteOwnershipDao())
            .claim(ActiveCommuteSource.Routine("r1"), "run-r")
        first.close()

        val repository = RoomActiveCommuteOwnershipRepository(openDatabase().activeCommuteOwnershipDao())
        assertEquals(
            ActiveCommuteOwnership(ActiveCommuteSource.Routine("r1"), "run-r"),
            repository.currentOwner(),
        )
    }

    @Test
    fun `event claim replaces routine globally and stale routine cannot clean event content`() = runTest {
        val repository = RoomActiveCommuteOwnershipRepository(openInMemoryDatabase().activeCommuteOwnershipDao())
        repository.claim(ActiveCommuteSource.Routine("r1"), "run-r")
        repository.claim(ActiveCommuteSource.OneTimeEvent("e1"), "run-e")

        assertFalse(repository.isOwner(ActiveCommuteSource.Routine("r1"), "run-r"))
        assertTrue(repository.isOwner(ActiveCommuteSource.OneTimeEvent("e1"), "run-e"))
    }

    @Test
    fun `new event replaces old event and stale event cannot clean replacement content`() = runTest {
        val repository = RoomActiveCommuteOwnershipRepository(openInMemoryDatabase().activeCommuteOwnershipDao())
        repository.claim(ActiveCommuteSource.OneTimeEvent("e1"), "run-a")
        repository.claim(ActiveCommuteSource.OneTimeEvent("e2"), "run-b")

        assertFalse(repository.isOwner(ActiveCommuteSource.OneTimeEvent("e1"), "run-a"))
        assertTrue(repository.isOwner(ActiveCommuteSource.OneTimeEvent("e2"), "run-b"))
    }

    @Test
    fun `routine claim replaces event globally and stale event cannot clean routine content`() = runTest {
        val repository = RoomActiveCommuteOwnershipRepository(openInMemoryDatabase().activeCommuteOwnershipDao())
        repository.claim(ActiveCommuteSource.OneTimeEvent("e1"), "run-e")
        repository.claim(ActiveCommuteSource.Routine("r1"), "run-r")

        assertFalse(repository.isOwner(ActiveCommuteSource.OneTimeEvent("e1"), "run-e"))
        assertTrue(repository.isOwner(ActiveCommuteSource.Routine("r1"), "run-r"))
    }

    @Test
    fun `stale owner cannot release replacement while current owner can release itself`() = runTest {
        val repository = RoomActiveCommuteOwnershipRepository(openInMemoryDatabase().activeCommuteOwnershipDao())
        repository.claim(ActiveCommuteSource.Routine("r1"), "run-r")
        repository.claim(ActiveCommuteSource.OneTimeEvent("e1"), "run-e")

        assertFalse(repository.releaseIfOwner(ActiveCommuteSource.Routine("r1"), "run-r"))
        assertTrue(repository.isOwner(ActiveCommuteSource.OneTimeEvent("e1"), "run-e"))
        assertTrue(repository.releaseIfOwner(ActiveCommuteSource.OneTimeEvent("e1"), "run-e"))
        assertEquals(null, repository.currentOwner())
    }
}
