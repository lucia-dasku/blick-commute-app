package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test
    fun existingRoutineBecomesLineDirectionAndJourneyIdsRemainNull() {
        helper.createDatabase(DB_NAME, 4).apply {
            execSQL(
                """INSERT INTO routines
                (id,name,siteId,siteName,transportMode,lineId,lineDesignation,directionCode,destinationLabel,
                 activeDaysMask,startTimeMinutes,endTimeMinutes,enabled,pausedDateEpochDay)
                VALUES ('r1','Morning',9192,'Slussen','METRO',14,'14',1,'Mörby centrum',31,420,540,1,NULL)""",
            )
            close()
        }
        val database = helper.runMigrationsAndValidate(DB_NAME, 5, true, MIGRATION_4_5)
        database.query("SELECT routineType, journeyOriginId, journeyDestinationId FROM routines WHERE id='r1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("LINE_DIRECTION", cursor.getString(0))
            assertEquals(null, cursor.getString(1))
            assertEquals(null, cursor.getString(2))
        }
        database.close()
    }

    private companion object { const val DB_NAME = "migration-4-5" }
}
