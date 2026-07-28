package se.blick.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import se.blick.app.BuildConfig
import se.blick.app.data.remote.BlickApiClient
import se.blick.app.data.remote.RetrofitBlickApiClient
import se.blick.app.data.remote.RetrofitBlickApiService
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

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideRetrofit(json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
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
