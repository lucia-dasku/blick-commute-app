package se.blick.app.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RoutineEntity::class, StaleSnapshotEntity::class], version = 2, exportSchema = true)
abstract class BlickDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun staleSnapshotDao(): StaleSnapshotDao
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
