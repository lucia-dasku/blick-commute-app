package se.blick.app.data.local.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveCommuteOwnershipDaoTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        BlickDatabase::class.java,
    ).build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun claimReplacesTheGlobalOwnerAcrossSourceTypes() = runTest {
        val dao = database.activeCommuteOwnershipDao()
        dao.claim(ActiveCommuteOwnershipEntity(sourceType = "ROUTINE", sourceId = "r1", ownerRunId = "run-r"))
        dao.claim(
            ActiveCommuteOwnershipEntity(
                sourceType = "ONE_TIME_EVENT",
                sourceId = "e1",
                ownerRunId = "run-e",
            ),
        )

        assertEquals(
            ActiveCommuteOwnershipEntity(
                sourceType = "ONE_TIME_EVENT",
                sourceId = "e1",
                ownerRunId = "run-e",
            ),
            dao.get(),
        )
    }

    @Test
    fun staleRunCannotReleaseTheCurrentOwner() = runTest {
        val dao = database.activeCommuteOwnershipDao()
        dao.claim(
            ActiveCommuteOwnershipEntity(
                sourceType = "ONE_TIME_EVENT",
                sourceId = "e1",
                ownerRunId = "run-e",
            ),
        )

        assertEquals(0, dao.releaseIfOwner("ROUTINE", "r1", "run-r"))
        assertEquals(1, dao.releaseIfOwner("ONE_TIME_EVENT", "e1", "run-e"))
        assertNull(dao.get())
    }
}
