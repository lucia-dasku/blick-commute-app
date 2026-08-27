package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    private val databaseName = "migration-8-9"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test fun addsOneTimeEventsWithoutChangingExistingRoutines() {
        helper.createDatabase(databaseName, 8).apply {
            execSQL(
                """INSERT INTO routines (
                    id, name, siteId, siteName, transportMode, lineId, lineDesignation,
                    directionCode, destinationLabel, activeDaysMask, startTimeMinutes,
                    endTimeMinutes, enabled, pausedDateEpochDay, routineType, journeyOriginId,
                    journeyOriginName, journeyDestinationId, journeyDestinationName,
                    allowedJourneyTransportModes, changesPreference, label
                ) VALUES ('r1','Routine',1,'Stop','BUS',1,'1',1,'End',1,420,480,1,NULL,
                    'LINE_DIRECTION',NULL,NULL,NULL,NULL,'METRO,TRAIN,BUS,TRAM,FERRY','BOTH',NULL)""",
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 9, true, MIGRATION_8_9).use { db ->
            db.query("SELECT name FROM routines WHERE id='r1'").use {
                it.moveToFirst()
                assertEquals("Routine", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM one_time_events").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
