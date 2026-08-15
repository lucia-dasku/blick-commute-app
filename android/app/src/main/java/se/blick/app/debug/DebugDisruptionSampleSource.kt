package se.blick.app.debug

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation

/**
 * Debug-only source of synthetic [DisruptionPresentation] samples, one per [DisruptionEffect],
 * for manually exercising the notification classifier's real rendering path without needing a
 * real live disruption to happen to exist (see
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel.showDebugTestNotification]).
 *
 * This interface — and [DebugDisruptionSampleSourceModule]'s [BindsOptionalOf] declaration below
 * — live in `main`, compiled into every build variant, but that declaration alone never provides
 * a CONCRETE instance. The actual sample text only exists in the `debug` build variant's own
 * source set (`app/src/debug/java/.../RealDebugDisruptionSampleSource.kt`), which supplies the
 * only `@Provides` binding for this type. A release build's Hilt graph never sees that binding
 * at all — not merely a value hidden behind a runtime `BuildConfig.DEBUG` check — so
 * `Optional<DebugDisruptionSampleSource>` resolves to [java.util.Optional.empty] there
 * structurally, by which source set actually got compiled, rather than by trusting every call
 * site to remember a flag check.
 */
fun interface DebugDisruptionSampleSource {
    fun sampleFor(effect: DisruptionEffect): DisruptionPresentation
}

/** Declares [DebugDisruptionSampleSource] as an OPTIONAL dependency of this Hilt graph — see
 * that interface's own doc for why this, rather than a required binding, is what lets ordinary
 * production code (the debug-gated section of [se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel])
 * depend on it directly, compiling identically in every build variant, while the concrete
 * sample DATA itself is only ever present in the `debug` variant's own compiled output. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugDisruptionSampleSourceModule {
    @BindsOptionalOf
    abstract fun bindDebugDisruptionSampleSource(): DebugDisruptionSampleSource
}
