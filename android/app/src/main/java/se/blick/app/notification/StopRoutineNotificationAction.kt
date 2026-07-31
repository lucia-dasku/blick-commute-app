package se.blick.app.notification

import se.blick.app.data.repository.RoutineRepository
import se.blick.app.scheduling.DeviceZoneProvider
import se.blick.app.scheduling.RoutineScheduler
import java.time.Clock
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The effect of tapping the ongoing notification's Stop action (the "Stop/Unpin" control the
 * Live Update spec requires) — kept as its own plain, Hilt-injected class, separate from
 * [StopRoutineNotificationReceiver], so this logic is unit-testable with fakes rather than only
 * reachable through a manifest-registered [android.content.BroadcastReceiver] (see
 * [se.blick.app.scheduling.RoutineScheduleReconciler] for the same split, used by
 * [se.blick.app.scheduling.BootCompletedReceiver]).
 *
 * Stopping today's active window early is given exactly the same effect as the existing
 * "pause for today" control on the routine details screen (`RoutineDetailsViewModel.pauseToday`)
 * — writing [se.blick.app.domain.model.CommuteRoutine.pausedDate] to today's date — rather than
 * inventing a separate mechanism. [RoutineActiveWindowWorker][se.blick.app.scheduling.RoutineActiveWindowWorker]
 * already re-reads the routine on every ~30-second loop tick and breaks out once
 * `pausedDate == today`, so this alone stops that loop (and, via its own `finally`, removes the
 * notification) within one tick. [RoutineNotifier.remove] is still called directly here too, so
 * the notification disappears immediately on tap rather than up to 30 seconds later.
 *
 * "Today" is deliberately resolved from [clock] combined with [deviceZoneProvider]'s CURRENT
 * zone (mirroring the worker's own `zonedNow()`), never a zone-less `LocalDate.now(clock)` —
 * using the worker's own device-zone definition of "today" is what guarantees this write and the
 * worker's own read of it agree, even right around local midnight.
 */
@Singleton
class StopRoutineNotificationAction @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val routineScheduler: RoutineScheduler,
    private val routineNotifier: RoutineNotifier,
    private val clock: Clock,
    private val deviceZoneProvider: DeviceZoneProvider,
) {
    suspend fun stop(routineId: String) {
        val routine = routineRepository.getById(routineId)
        if (routine != null) {
            val today = ZonedDateTime.ofInstant(clock.instant(), deviceZoneProvider.currentZone()).toLocalDate()
            routineRepository.pauseForDate(routineId, today)
            routineScheduler.scheduleActivation(routine.copy(pausedDate = today))
        }
        routineNotifier.remove()
    }
}
