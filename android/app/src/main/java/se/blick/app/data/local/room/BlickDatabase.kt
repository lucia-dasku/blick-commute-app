package se.blick.app.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RoutineEntity::class, StaleSnapshotEntity::class, RoutineWorkOwnershipEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class BlickDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun staleSnapshotDao(): StaleSnapshotDao
    abstract fun routineWorkOwnershipDao(): RoutineWorkOwnershipDao
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
