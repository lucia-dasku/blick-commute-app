package se.blick.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.data.local.datastore.PreferencesAppSettingsDataStore
import se.blick.app.data.repository.DepartureRepository
import se.blick.app.data.repository.DirectionOptionsSource
import se.blick.app.data.repository.DisruptionRepository
import se.blick.app.data.repository.LiveDeparturesDirectionOptionsSource
import se.blick.app.data.repository.RemoteDepartureRepository
import se.blick.app.data.repository.RemoteDisruptionRepository
import se.blick.app.data.repository.RemoteStopRepository
import se.blick.app.data.repository.RoomRoutineRepository
import se.blick.app.data.repository.RoomStaleSnapshotRepository
import se.blick.app.data.repository.RoutineRepository
import se.blick.app.data.repository.StaleSnapshotRepository
import se.blick.app.data.repository.StopRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRoutineRepository(impl: RoomRoutineRepository): RoutineRepository

    @Binds
    @Singleton
    abstract fun bindDepartureRepository(impl: RemoteDepartureRepository): DepartureRepository

    @Binds
    @Singleton
    abstract fun bindDisruptionRepository(impl: RemoteDisruptionRepository): DisruptionRepository

    @Binds
    @Singleton
    abstract fun bindDirectionOptionsSource(impl: LiveDeparturesDirectionOptionsSource): DirectionOptionsSource

    @Binds
    @Singleton
    abstract fun bindAppSettingsDataStore(impl: PreferencesAppSettingsDataStore): AppSettingsDataStore

    @Binds
    @Singleton
    abstract fun bindStopRepository(impl: RemoteStopRepository): StopRepository

    @Binds
    @Singleton
    abstract fun bindStaleSnapshotRepository(impl: RoomStaleSnapshotRepository): StaleSnapshotRepository
}
