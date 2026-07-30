package se.blick.app.scheduling

import java.time.ZoneId

/**
 * The device's current local time zone — injected so scheduling resolves a routine's
 * wall-clock weekdays/start time/end time against the DEVICE's zone, never against
 * `Clock.systemUTC()` (see `di/TimeModule.kt`, deliberately UTC-only — it must never be
 * treated as this app's scheduling zone) or any other zone an injected [java.time.Clock]
 * happens to carry.
 *
 * A method, not a cached property or a value captured once at construction time: a genuine
 * device timezone change while the process stays alive (see Android's
 * `Intent.ACTION_TIMEZONE_CHANGED`, handled by `BlickApplication`'s receiver) must be picked
 * up the very next time [currentZone] is called, not frozen at whatever zone was in effect
 * when this provider was first injected.
 */
fun interface DeviceZoneProvider {
    fun currentZone(): ZoneId
}
