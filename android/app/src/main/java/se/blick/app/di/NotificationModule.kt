package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.notification.AndroidNotificationAvailabilityChecker
import se.blick.app.notification.AndroidPromotedNotificationChecker
import se.blick.app.notification.AndroidRoutineNotifier
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.NotificationAvailabilityStateStore
import se.blick.app.notification.PreferencesNotificationAvailabilityStateStore
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.notification.RoutineNotifier
import javax.inject.Singleton

/** Same `@Binds @Singleton` convention as [RepositoryModule] — there must only ever be one
 * [RoutineNotifier] instance, matching there only ever being one ongoing notification. Also
 * binds the shared [NotificationAvailabilityChecker] (see that interface's own doc) so
 * [AndroidRoutineNotifier], `RoutineActiveWindowWorker`, and the routine details screen all
 * read the exact same live availability state, the separate [PromotedNotificationChecker] (see
 * that interface's own doc on why it's a distinct concern), and [NotificationAvailabilityStateStore]
 * (see that interface's own doc) so `ForegroundNotificationRecovery` has one persisted place to
 * detect an unavailable-to-available transition across process recreation. */
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

    @Binds
    @Singleton
    abstract fun bindPromotedNotificationChecker(
        impl: AndroidPromotedNotificationChecker,
    ): PromotedNotificationChecker

    @Binds
    @Singleton
    abstract fun bindNotificationAvailabilityStateStore(
        impl: PreferencesNotificationAvailabilityStateStore,
    ): NotificationAvailabilityStateStore
}
