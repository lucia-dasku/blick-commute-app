package se.blick.app.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RoutineEntity::class], version = 1, exportSchema = true)
abstract class BlickDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
}
