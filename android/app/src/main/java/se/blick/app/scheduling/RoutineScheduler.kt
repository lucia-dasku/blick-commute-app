package se.blick.app.scheduling

import se.blick.app.domain.model.CommuteRoutine

/**
 * Represents scheduling a routine's active window (see the product doc's "Scheduling
 * and Android limitations" section). Deliberately an interface only in this scaffold —
 * no AlarmManager/WorkManager implementation exists yet.
 */
interface RoutineScheduler {
    fun scheduleActivation(routine: CommuteRoutine)
    fun cancelActivation(routineId: String)
}
