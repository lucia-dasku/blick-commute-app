package se.blick.app.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration9To10Test {
    private val databaseName = "migration-9-10"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlickDatabase::class.java,
    )

    @Test
    fun addsGlobalOwnershipWithoutChangingExistingRoutinesOrEvents() {
        helper.createDatabase(databaseName, 9).apply {
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
            execSQL(
                """INSERT INTO one_time_events (
                    id,label,name,originId,originName,destinationId,destinationName,
                    eventDateEpochDay,eventTimeMinutes,timeType,createdAtEpochMilli,updatedAtEpochMilli
                ) VALUES ('e1','EVENT','Concert','a','A','b','B',21000,1080,'ARRIVE_BY',1,1)""",
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 10, true, MIGRATION_9_10).use { db ->
            db.query("SELECT name FROM routines WHERE id='r1'").use {
                it.moveToFirst()
                assertEquals("Routine", it.getString(0))
            }
            db.query("SELECT name FROM one_time_events WHERE id='e1'").use {
                it.moveToFirst()
                assertEquals("Concert", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM active_commute_ownership").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
