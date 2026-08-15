package se.blick.app.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RoutineEntity::class,
        StaleSnapshotEntity::class,
        RoutineWorkOwnershipEntity::class,
        RoutineOccurrenceRuntimeEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class BlickDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun staleSnapshotDao(): StaleSnapshotDao
    abstract fun routineWorkOwnershipDao(): RoutineWorkOwnershipDao
    abstract fun routineOccurrenceRuntimeDao(): RoutineOccurrenceRuntimeDao
}

/** Adds [StaleSnapshotEntity]'s table — see that class's own doc for the durable stale-fallback
 * cache it backs. A plain additive migration (no existing column touched), matching
 * [StaleSnapshotEntity]'s Kotlin declaration column-for-column, including its `routines.id`
 * foreign key with `ON DELETE CASCADE`. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stale_snapshots` (
                `routineId` TEXT NOT NULL,
                `siteId` INTEGER NOT NULL,
                `lineId` INTEGER,
                `directionCode` INTEGER,
                `transportMode` TEXT NOT NULL,
                `fetchedAtEpochMilli` INTEGER NOT NULL,
                `departuresJson` TEXT NOT NULL,
                PRIMARY KEY(`routineId`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

/** Adds [RoutineWorkOwnershipEntity]'s table — see that class's own doc for the per-run
 * content-ownership tracking it backs. A plain additive migration, matching
 * [RoutineWorkOwnershipEntity]'s Kotlin declaration column-for-column, including its
 * `routines.id` foreign key with `ON DELETE CASCADE`. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routine_work_ownership` (
                `routineId` TEXT NOT NULL,
                `ownerWorkId` TEXT NOT NULL,
                PRIMARY KEY(`routineId`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

/** Adds [RoutineOccurrenceRuntimeEntity]'s table — see that class's own doc for the per-occurrence
 * hard-runtime-cap tracking it backs. A plain additive migration, matching
 * [RoutineOccurrenceRuntimeEntity]'s Kotlin declaration column-for-column, including its
 * `routines.id` foreign key with `ON DELETE CASCADE`. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routine_occurrence_runtime` (
                `routineId` TEXT NOT NULL,
                `occurrenceWindowEndEpochMilli` INTEGER NOT NULL,
                `monotonicStartElapsedRealtimeMillis` INTEGER NOT NULL,
                `bootCountAtStart` INTEGER NOT NULL,
                `hardStopEpochMilli` INTEGER NOT NULL,
                PRIMARY KEY(`routineId`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

/** Adds the explicit routine discriminator and Journey Planner identifiers without changing
 * existing line routines. Existing rows remain LINE_DIRECTION by SQL default. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `routineType` TEXT NOT NULL DEFAULT 'LINE_DIRECTION'")
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `journeyOriginId` TEXT")
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `journeyOriginName` TEXT")
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `journeyDestinationId` TEXT")
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `journeyDestinationName` TEXT")
    }
}

/** Adds the per-routine Journey Planner mode allow-list. Existing exact-destination routines
 * keep all regular SL modes enabled until the user narrows the selection. */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `routines` ADD COLUMN `allowedJourneyTransportModes` TEXT NOT NULL " +
                "DEFAULT 'METRO,TRAIN,BUS,TRAM,FERRY'",
        )
    }
}

/** Adds the per-routine exact-destination Direct/Both/With-changes preference (see
 * [se.blick.app.domain.model.ExactDestinationChangesPreference]'s own doc). Every existing row —
 * exact-destination or plain line-direction alike — defaults to `'BOTH'`, the pre-existing
 * unfiltered journey-eligibility behavior, so this migration changes no routine's observable
 * behavior by itself. */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `changesPreference` TEXT NOT NULL DEFAULT 'BOTH'")
    }
}
