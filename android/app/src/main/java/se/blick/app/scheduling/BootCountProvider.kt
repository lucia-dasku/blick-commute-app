package se.blick.app.scheduling

/**
 * A value that changes every time the device reboots — backed by `Settings.Global.BOOT_COUNT` in
 * production. Used alongside [ElapsedRealtimeProvider] to detect whether a persisted
 * [ElapsedRealtimeProvider.elapsedRealtimeMillis] value recorded by an earlier run is still
 * meaningful: that clock resets to zero on every reboot, so comparing a stale value from a
 * PREVIOUS boot directly against the current one would be meaningless — see
 * [se.blick.app.data.repository.RoutineOccurrenceRuntimeRepository]'s own doc for how this is
 * used as the signal to fall back to a conservative wall-clock check instead.
 */
fun interface BootCountProvider {
    fun currentBootCount(): Int
}
