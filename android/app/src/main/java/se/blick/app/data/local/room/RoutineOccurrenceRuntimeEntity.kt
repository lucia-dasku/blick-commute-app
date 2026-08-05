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
 * - [monotonicStartElapsedRealtimeMillis] is [android.os.SystemClock.elapsedRealtime] at the
 *   moment this occurrence FIRST entered foreground execution — the authoritative measurement
 *   while [bootCountAtStart] still matches the current boot.
 * - [bootCountAtStart] is the device's boot count (see [se.blick.app.scheduling.BootCountProvider])
 *   at that same moment — if the CURRENT boot count no longer matches, the device has rebooted
 *   since, [monotonicStartElapsedRealtimeMillis] has been reset by the OS and can no longer be
 *   compared against directly, and [hardStopEpochMilli] must be used instead.
 * - [hardStopEpochMilli] is the wall-clock instant (epoch milliseconds) at which this occurrence
 *   will have run for `HARD_FOREGROUND_RUNTIME_CAP_MINUTES`, computed ONCE from the wall clock at
 *   the same moment as the other two fields — an absolute reference point that survives a reboot
 *   (unlike the monotonic fields above), used as the conservative fallback so a reboot can never
 *   silently grant a fresh allowance.
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
