package se.blick.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import se.blick.app.data.local.room.MIGRATION_1_2
import se.blick.app.data.local.room.MIGRATION_2_3
import se.blick.app.data.local.room.MIGRATION_3_4
import se.blick.app.data.local.room.MIGRATION_4_5
import se.blick.app.data.local.room.MIGRATION_5_6
import se.blick.app.data.local.room.MIGRATION_6_7
import se.blick.app.data.local.room.MIGRATION_7_8
import se.blick.app.data.local.room.MIGRATION_8_9
import se.blick.app.data.local.room.OneTimeEventDao
import se.blick.app.data.local.room.RoutineDao
import se.blick.app.data.local.room.BlickDatabase
import se.blick.app.data.local.room.RoutineOccurrenceRuntimeDao
import se.blick.app.data.local.room.RoutineWorkOwnershipDao
import se.blick.app.data.local.room.StaleSnapshotDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlickDatabase =
        Room.databaseBuilder(context, BlickDatabase::class.java, "blick.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )
            .build()

    @Provides
    fun provideRoutineDao(database: BlickDatabase): RoutineDao = database.routineDao()

    @Provides
    fun provideStaleSnapshotDao(database: BlickDatabase): StaleSnapshotDao = database.staleSnapshotDao()

    @Provides
    fun provideRoutineWorkOwnershipDao(database: BlickDatabase): RoutineWorkOwnershipDao =
        database.routineWorkOwnershipDao()

    @Provides
    fun provideRoutineOccurrenceRuntimeDao(database: BlickDatabase): RoutineOccurrenceRuntimeDao =
        database.routineOccurrenceRuntimeDao()

    @Provides
    fun provideOneTimeEventDao(database: BlickDatabase): OneTimeEventDao = database.oneTimeEventDao()
}
