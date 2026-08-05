package se.blick.app.di

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import se.blick.app.scheduling.BootCountProvider
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.ElapsedRealtimeProvider
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

/**
 * Provides the single system [Clock] instance used anywhere a current instant is needed
 * (see [se.blick.app.domain.usecase.GetLiveDeparturesUseCase]). Kept behind DI, rather than
 * calling `Instant.now()`/`Clock.systemUTC()` directly at the call site, purely so tests
 * can substitute a fixed [Clock] without touching production code.
 *
 * [Clock.systemUTC] is deliberately a fixed, zone-less instant source — NOT this app's
 * scheduling zone. Anything that needs to resolve a routine's wall-clock weekday/start/end
 * time (see [se.blick.app.scheduling.NextOccurrenceCalculator]) must combine this [Clock]'s
 * instant with [DeviceZoneProvider]'s current zone instead (typically via
 * `ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone())`), never with
 * this clock's own zone.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /** [ZoneId.systemDefault] re-consults the platform's current default zone on every call
     * rather than caching it once — required so a live device timezone change is reflected
     * the next time [DeviceZoneProvider.currentZone] is called (see that interface's own
     * doc), not just after a process restart. */
    @Provides
    @Singleton
    fun provideDeviceZoneProvider(): DeviceZoneProvider = DeviceZoneProvider { ZoneId.systemDefault() }

    /** See [ElapsedRealtimeProvider]'s own doc on why this — not [Clock] — backs
     * [se.blick.app.scheduling.RoutineActiveWindowWorker]'s hard runtime-cap measurement. */
    @Provides
    @Singleton
    fun provideElapsedRealtimeProvider(): ElapsedRealtimeProvider = ElapsedRealtimeProvider { SystemClock.elapsedRealtime() }

    /** `Settings.Global.BOOT_COUNT` is a public, permission-free system setting that increments
     * on every device boot — see [BootCountProvider]'s own doc. Defaults to 0 in the vanishingly
     * unlikely case the setting is unreadable, which only means a reboot could fail to be
     * detected as such this one time; [ElapsedRealtimeProvider] itself already resets to zero on
     * every real reboot regardless. */
    @Provides
    @Singleton
    fun provideBootCountProvider(@ApplicationContext context: Context): BootCountProvider = BootCountProvider {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }
}
