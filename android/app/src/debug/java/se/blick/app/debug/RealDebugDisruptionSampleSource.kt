package se.blick.app.debug

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation

/**
 * The only concrete source of synthetic disruption sample text in this codebase — exists
 * exclusively in the `debug` build variant's own source set (see
 * [DebugDisruptionSampleSource]'s own doc for why that alone is what keeps this data out of a
 * release build, structurally). Every headline is prefixed `"[TEST]"` so it can never be
 * mistaken for a real SL notice if it somehow ends up on screen during manual testing.
 */
private object RealDebugDisruptionSampleSource : DebugDisruptionSampleSource {
    override fun sampleFor(effect: DisruptionEffect): DisruptionPresentation = when (effect) {
        DisruptionEffect.DELAYS ->
            DisruptionPresentation("[TEST] Line 14 is delayed by 5 minutes.", "Synthetic sample for DELAYS.", effect)
        DisruptionEffect.NO_SERVICE ->
            DisruptionPresentation("[TEST] No service on this line right now.", "Synthetic sample for NO_SERVICE.", effect)
        DisruptionEffect.REDUCED_SERVICE ->
            DisruptionPresentation("[TEST] Fewer departures than usual on this line.", "Synthetic sample for REDUCED_SERVICE.", effect)
        DisruptionEffect.ROUTE_CHANGE ->
            DisruptionPresentation("[TEST] This line is being rerouted.", "Synthetic sample for ROUTE_CHANGE.", effect)
        DisruptionEffect.STOP_CHANGE ->
            DisruptionPresentation("[TEST] A stop on this route has moved.", "Synthetic sample for STOP_CHANGE.", effect)
        DisruptionEffect.REPLACEMENT_SERVICE ->
            DisruptionPresentation("[TEST] Replacement buses are running instead.", "Synthetic sample for REPLACEMENT_SERVICE.", effect)
        DisruptionEffect.STATION_ACCESS ->
            DisruptionPresentation("[TEST] A station entrance is closed.", "Synthetic sample for STATION_ACCESS.", effect)
        DisruptionEffect.ACCESSIBILITY_ISSUE ->
            DisruptionPresentation("[TEST] A lift is out of service.", "Synthetic sample for ACCESSIBILITY_ISSUE.", effect)
        DisruptionEffect.DISRUPTION ->
            DisruptionPresentation("[TEST] Something is affecting this route.", "Synthetic sample for the generic DISRUPTION fallback.", effect)
    }
}

/** Supplies the only real [DebugDisruptionSampleSource] binding that ever exists — see that
 * interface's own doc. Compiled solely into the `debug` build variant. */
@Module
@InstallIn(SingletonComponent::class)
object RealDebugDisruptionSampleSourceModule {
    @Provides
    fun provideDebugDisruptionSampleSource(): DebugDisruptionSampleSource = RealDebugDisruptionSampleSource
}
