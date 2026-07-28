package se.blick.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides the single system [Clock] instance used anywhere a current instant is needed
 * (see [se.blick.app.domain.usecase.GetLiveDeparturesUseCase]). Kept behind DI, rather than
 * calling `Instant.now()`/`Clock.systemUTC()` directly at the call site, purely so tests
 * can substitute a fixed [Clock] without touching production code.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
