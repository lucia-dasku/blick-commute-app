package se.blick.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import se.blick.app.data.local.room.RoutineDao
import se.blick.app.data.local.room.BlickDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlickDatabase =
        Room.databaseBuilder(context, BlickDatabase::class.java, "blick.db").build()

    @Provides
    fun provideRoutineDao(database: BlickDatabase): RoutineDao = database.routineDao()
}
