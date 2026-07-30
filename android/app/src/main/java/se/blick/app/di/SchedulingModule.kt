package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.scheduling.WorkManagerRoutineScheduler
import javax.inject.Singleton

/** Same `@Binds @Singleton` convention as [NotificationModule]/[RepositoryModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulingModule {

    @Binds
    @Singleton
    abstract fun bindRoutineScheduler(impl: WorkManagerRoutineScheduler): RoutineScheduler
}
