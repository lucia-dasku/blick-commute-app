package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.notification.AndroidNotificationAvailabilityChecker
import se.blick.app.notification.AndroidRoutineNotifier
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.RoutineNotifier
import javax.inject.Singleton

/** Same `@Binds @Singleton` convention as [RepositoryModule] — there must only ever be one
 * [RoutineNotifier] instance, matching there only ever being one ongoing notification. Also
 * binds the shared [NotificationAvailabilityChecker] (see that interface's own doc) so
 * [AndroidRoutineNotifier], `RoutineActiveWindowWorker`, and the routine details screen all
 * read the exact same live availability state. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    @Binds
    @Singleton
    abstract fun bindRoutineNotifier(impl: AndroidRoutineNotifier): RoutineNotifier

    @Binds
    @Singleton
    abstract fun bindNotificationAvailabilityChecker(
        impl: AndroidNotificationAvailabilityChecker,
    ): NotificationAvailabilityChecker
}
