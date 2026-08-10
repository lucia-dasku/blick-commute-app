package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test
    fun existingRoutineStartsWithAllRegularJourneyModesAllowed() {
        helper.createDatabase(DB_NAME, 5).apply {
            execSQL(
                """INSERT INTO routines
                (id,name,siteId,siteName,transportMode,lineId,lineDesignation,directionCode,destinationLabel,
                 activeDaysMask,startTimeMinutes,endTimeMinutes,enabled,pausedDateEpochDay,routineType,
                 journeyOriginId,journeyOriginName,journeyDestinationId,journeyDestinationName)
                VALUES ('r1','Exact',9192,'Slussen','UNKNOWN',NULL,NULL,NULL,NULL,31,420,540,1,NULL,
                'EXACT_DESTINATION','origin','Slussen','destination','Liljeholmen')""",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(DB_NAME, 6, true, MIGRATION_5_6)
        database.query("SELECT allowedJourneyTransportModes FROM routines WHERE id='r1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("METRO,TRAIN,BUS,TRAM,FERRY", cursor.getString(0))
        }
        database.close()
    }

    private companion object { const val DB_NAME = "migration-5-6" }
}
