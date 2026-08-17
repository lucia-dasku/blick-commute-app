package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test
    fun existingRoutineRemainsUnlabeledAndCanStoreALabelAfterMigration() {
        helper.createDatabase(DB_NAME, 7).apply {
            execSQL(
                """INSERT INTO routines
                (id,name,siteId,siteName,transportMode,lineId,lineDesignation,directionCode,destinationLabel,
                 activeDaysMask,startTimeMinutes,endTimeMinutes,enabled,pausedDateEpochDay,routineType,
                 journeyOriginId,journeyOriginName,journeyDestinationId,journeyDestinationName,
                 allowedJourneyTransportModes,changesPreference)
                VALUES ('r1','Line',9145,'Origin','METRO',14,'14',1,'Destination',31,420,540,1,NULL,
                'LINE_DIRECTION',NULL,NULL,NULL,NULL,'METRO,TRAIN,BUS,TRAM,FERRY','BOTH')""",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(DB_NAME, 8, true, MIGRATION_7_8)
        database.query("SELECT label FROM routines WHERE id='r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        database.execSQL("UPDATE routines SET label='HOME' WHERE id='r1'")
        database.query("SELECT label FROM routines WHERE id='r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HOME", cursor.getString(0))
        }
        database.close()
    }

    private companion object { const val DB_NAME = "migration-7-8" }
}
