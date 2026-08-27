package se.blick.app.data.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class OneTimeEventDaoTest {
    private lateinit var database: BlickDatabase
    private lateinit var dao: OneTimeEventDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BlickDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.oneTimeEventDao()
    }

    @After fun tearDown() = database.close()

    @Test fun saveLoadEditAndDelete() = runTest {
        val event = sampleEvent()
        dao.upsert(event.toEntity())
        assertEquals(event, dao.getById(event.id)?.toDomain())

        dao.upsert(event.copy(name = "Updated").toEntity())
        assertEquals("Updated", dao.getById(event.id)?.name)

        dao.deleteById(event.id)
        assertNull(dao.getById(event.id))
    }

    @Test fun eventsAreLoadedChronologically() = runTest {
        val later = sampleEvent().copy(id = "later", date = LocalDate.of(2026, 10, 2))
        val earlier = sampleEvent().copy(id = "earlier", date = LocalDate.of(2026, 9, 17))
        dao.upsert(later.toEntity())
        dao.upsert(earlier.toEntity())
        assertEquals(listOf("earlier", "later"), dao.observeAll().first().map(OneTimeEventEntity::id))
    }

    private fun sampleEvent() = OneTimeEvent(
        id = "event",
        label = OneTimeEventLabel.APPOINTMENT,
        name = "Dentist",
        originId = "A",
        originName = "Home",
        destinationId = "B",
        destinationName = "Clinic",
        date = LocalDate.of(2026, 10, 8),
        time = LocalTime.of(14, 15),
        timeType = OneTimeEventTimeType.LEAVE_AT,
    )
}
