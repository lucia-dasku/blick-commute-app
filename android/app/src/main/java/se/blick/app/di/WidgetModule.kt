package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.widget.GlanceRoutineWidgetUpdater
import se.blick.app.widget.RoutineWidgetUpdater
import javax.inject.Singleton

/** Same `@Binds @Singleton` convention as [NotificationModule] — there is one shared
 * [RoutineWidgetUpdater], matching there being one shared [se.blick.app.notification.RoutineNotifier]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {

    @Binds
    @Singleton
    abstract fun bindRoutineWidgetUpdater(impl: GlanceRoutineWidgetUpdater): RoutineWidgetUpdater
}
