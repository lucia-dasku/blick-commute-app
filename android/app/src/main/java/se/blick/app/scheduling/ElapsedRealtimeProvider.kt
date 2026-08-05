package se.blick.app.scheduling

/**
 * Monotonic, boot-anchored elapsed time — backed by [android.os.SystemClock.elapsedRealtime] in
 * production, injected so tests can control it directly. Deliberately NOT [java.time.Clock]/
 * `Instant.now()`: wall-clock time can be moved forward or backward at any moment by the user,
 * NTP sync, or the network, none of which reflect how much real time has actually elapsed on the
 * device — see [RoutineActiveWindowWorker]'s own `HARD_FOREGROUND_RUNTIME_CAP_MINUTES` doc for
 * why its runtime safety cap is measured against this instead of the injected wall-clock
 * [java.time.Clock] used everywhere else in that file. Resets to zero on every device reboot —
 * see [BootCountProvider] and [se.blick.app.data.repository.RoutineOccurrenceRuntimeRepository]
 * for how that reset is detected and handled without silently granting a fresh allowance.
 */
fun interface ElapsedRealtimeProvider {
    fun elapsedRealtimeMillis(): Long
}
