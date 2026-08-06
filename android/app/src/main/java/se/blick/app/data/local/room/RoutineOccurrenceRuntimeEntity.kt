package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Persists enough state for [se.blick.app.scheduling.RoutineActiveWindowWorker]'s
 * `HARD_FOREGROUND_RUNTIME_CAP_MINUTES` safety cap to belong to the whole routine OCCURRENCE
 * (one continuous active window), not to a single worker instance — see that worker's own class
 * doc. A replacement worker (WorkManager restarting this routine's unique work while the same
 * occurrence is still open, or a fresh process after a reboot) must continue counting from where
 * the ORIGINAL run started, rather than resetting the clock and granting a fresh allowance.
 *
 * One row per routine ([routineId] is both the primary key and, via the declared [ForeignKey],
 * a reference to `routines.id`) — `ON DELETE CASCADE` means deleting a routine automatically
 * removes its runtime record too, matching [RoutineWorkOwnershipEntity]'s identical convention.
 *
 * - [occurrenceWindowEndEpochMilli] identifies WHICH occurrence this state belongs to (a
 *   routine's `NextOccurrence.ActiveNow.windowEnd`, unique per calendar occurrence — two
 *   different weeks' occurrences of the same routine never share the same end instant). A
 *   worker that finds a persisted row for a DIFFERENT occurrence than the one it's about to run
 *   knows the old row is stale and starts fresh instead of reusing it.
 * - [monotonicStartElapsedRealtimeMillis] is ORDINARILY [android.os.SystemClock.elapsedRealtime]
 *   at the moment this occurrence FIRST entered foreground execution — the authoritative
 *   measurement while [bootCountAtStart] still matches the current boot. When a daylight-saving
 *   transition has shortened the real gap to this routine's NEXT occurrence (see
 *   `RoutineActiveWindowWorker`'s own `effectiveHardCapMinutes`), this may instead be recorded
 *   EARLIER than the true foreground-entry instant, by exactly that shortfall — so that the
 *   worker's own unchanged, fixed-`HARD_FOREGROUND_RUNTIME_CAP_MINUTES` comparison fires sooner,
 *   at the correct REDUCED effective cap, without needing a separate persisted duration field.
 * - [bootCountAtStart] is the device's boot count (see [se.blick.app.scheduling.BootCountProvider])
 *   at that same moment — if the CURRENT boot count no longer matches, the device has rebooted
 *   since, [monotonicStartElapsedRealtimeMillis] has been reset by the OS and can no longer be
 *   compared against directly, and [hardStopEpochMilli] must be used instead.
 * - [hardStopEpochMilli] is the wall-clock instant (epoch milliseconds) at which this occurrence
 *   will have run for its own effective cap (ordinarily `HARD_FOREGROUND_RUNTIME_CAP_MINUTES`,
 *   but see [monotonicStartElapsedRealtimeMillis] above for when it is reduced), computed ONCE
 *   from the wall clock at the same moment as the other two fields, using that SAME possibly-
 *   shifted start — an absolute reference point that survives a reboot (unlike the monotonic
 *   fields above), used as the conservative fallback so a reboot can never silently grant a
 *   fresh allowance.
 *
 * This row is deliberately left in place once an occurrence's cap is reached, rather than
 * cleared — see `RoutineActiveWindowWorker.doWork`'s own comment on why clearing it would let a
 * replacement worker or reboot-recovered process, for this SAME still-open occurrence, wrongly
 * treat it as fresh and grant a brand new allowance. It is cleared normally for every OTHER way
 * an occurrence can end (window closed naturally, notifications unavailable, a handled failure),
 * and a genuinely NEW occurrence (a different [occurrenceWindowEndEpochMilli]) always replaces an
 * old row outright via the normal upsert regardless of whether it was ever cleared.
 */
@Entity(
    tableName = "routine_occurrence_runtime",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoutineOccurrenceRuntimeEntity(
    @PrimaryKey val routineId: String,
    val occurrenceWindowEndEpochMilli: Long,
    val monotonicStartElapsedRealtimeMillis: Long,
    val bootCountAtStart: Int,
    val hardStopEpochMilli: Long,
)
