package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.scheduling.NotificationRecoveryCoordinator
import se.blick.app.scheduling.NotificationRecoveryReporter
import se.blick.app.scheduling.RoutineScheduler
import se.blick.app.scheduling.WorkManagerRoutineScheduler
import se.blick.app.scheduling.OneTimeEventScheduler
import se.blick.app.scheduling.WorkManagerOneTimeEventScheduler
import javax.inject.Singleton

/** Same `@Binds @Singleton` convention as [NotificationModule]/[RepositoryModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulingModule {

    @Binds
    @Singleton
    abstract fun bindRoutineScheduler(impl: WorkManagerRoutineScheduler): RoutineScheduler

    @Binds
    @Singleton
    abstract fun bindOneTimeEventScheduler(impl: WorkManagerOneTimeEventScheduler): OneTimeEventScheduler

    /** Exposes the same `@Singleton` [NotificationRecoveryCoordinator] instance (already
     * directly injectable via its own `@Inject constructor`, e.g. into `BlickApplication`)
     * through its narrower [NotificationRecoveryReporter] surface too — see that interface's
     * own doc for why [se.blick.app.scheduling.RoutineActiveWindowWorker] and
     * `RoutineDetailsViewModel` depend on it instead of the concrete coordinator. */
    @Binds
    @Singleton
    abstract fun bindNotificationRecoveryReporter(impl: NotificationRecoveryCoordinator): NotificationRecoveryReporter
}
