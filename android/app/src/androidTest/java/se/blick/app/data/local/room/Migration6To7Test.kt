package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6To7Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test
    fun existingExactDestinationRoutineDefaultsToBothChangesPreference() {
        helper.createDatabase(DB_NAME, 6).apply {
            execSQL(
                """INSERT INTO routines
                (id,name,siteId,siteName,transportMode,lineId,lineDesignation,directionCode,destinationLabel,
                 activeDaysMask,startTimeMinutes,endTimeMinutes,enabled,pausedDateEpochDay,routineType,
                 journeyOriginId,journeyOriginName,journeyDestinationId,journeyDestinationName,allowedJourneyTransportModes)
                VALUES ('r1','Exact',9192,'Slussen','UNKNOWN',NULL,NULL,NULL,NULL,31,420,540,1,NULL,
                'EXACT_DESTINATION','origin','Slussen','destination','Liljeholmen','METRO,TRAIN,BUS,TRAM,FERRY')""",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(DB_NAME, 7, true, MIGRATION_6_7)
        database.query("SELECT changesPreference FROM routines WHERE id='r1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("BOTH", cursor.getString(0))
        }
        database.close()
    }

    @Test
    fun existingLineDirectionRoutineAlsoDefaultsToBothChangesPreference() {
        // A LINE_DIRECTION routine never reads this field (see
        // ExactDestinationChangesPreference's own doc) -- this proves the migration still
        // applies the same safe default to it rather than leaving the column null or failing
        // the ALTER for a row shaped differently from the exact-destination one above.
        helper.createDatabase(DB_NAME, 6).apply {
            execSQL(
                """INSERT INTO routines
                (id,name,siteId,siteName,transportMode,lineId,lineDesignation,directionCode,destinationLabel,
                 activeDaysMask,startTimeMinutes,endTimeMinutes,enabled,pausedDateEpochDay,routineType,
                 journeyOriginId,journeyOriginName,journeyDestinationId,journeyDestinationName,allowedJourneyTransportModes)
                VALUES ('r2','Line',9145,'Fruängen','METRO',14,'14',1,'T-Centralen',31,420,540,1,NULL,
                'LINE_DIRECTION',NULL,NULL,NULL,NULL,'METRO,TRAIN,BUS,TRAM,FERRY')""",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(DB_NAME, 7, true, MIGRATION_6_7)
        database.query("SELECT changesPreference FROM routines WHERE id='r2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("BOTH", cursor.getString(0))
        }
        database.close()
    }

    private companion object { const val DB_NAME = "migration-6-7" }
}
