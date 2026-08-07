package se.blick.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import se.blick.app.BuildConfig
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.RetrofitBlickApiClient
import se.blick.app.data.remote.RetrofitBlickApiService
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The backend base URL is read from [BuildConfig.BACKEND_BASE_URL], which is generated
 * from the `BLICK_BACKEND_BASE_URL` Gradle property at build time (see
 * app/build.gradle.kts `defaultConfig` and android/README.md "Pointing at a deployed
 * backend"). Nothing is hardcoded here so pointing this app at a real deployment is a
 * one-line Gradle property change, not a source edit.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Whole-call timeout (covers connecting, all redirects, and reading the complete response
     * body -- not just a single read/write operation) applied to every backend request through
     * the shared [OkHttpClient] below. Most importantly bounds
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s own departures fetch, the first thing
     * its ~30-second refresh tick does every iteration (see that worker's own
     * `ACTIVE_WINDOW_REFRESH_INTERVAL_MS` doc): without this, a very slow or trickling response
     * (one that keeps arriving, however slowly) could consume an unbounded part of that tick,
     * since OkHttp's own default per-operation read/write timeouts never fire as long as SOME
     * bytes keep arriving before each one individually elapses -- only a whole-call timeout
     * bounds the total.
     *
     * Comfortably below that 30-second tick budget, and deliberately looser than
     * [se.blick.app.scheduling.DISRUPTIONS_FETCH_TIMEOUT_MS]'s own 5-second bound: departures is
     * the primary, always-fetched-first data for that tick, not the secondary, already
     * tightly-bounded disruptions fetch. A call that times out surfaces to callers as an ordinary
     * [java.io.IOException] (OkHttp's own call-timeout failure), exactly like any other
     * network-level failure -- [se.blick.app.domain.usecase.GetLiveDeparturesUseCase] already
     * catches that broadly and falls back to its existing stale-snapshot/offline state, so no
     * separate handling is needed for this specifically.
     */
    internal const val CALL_TIMEOUT_MS = 10_000L

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(json: Json, okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): RetrofitBlickApiService =
        retrofit.create(RetrofitBlickApiService::class.java)

    @Provides
    @Singleton
    fun provideApiClient(service: RetrofitBlickApiService): BlickApiClient =
        RetrofitBlickApiClient(service)
}
