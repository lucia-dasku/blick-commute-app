package se.blick.app.billing

import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.RoutineType

object RoutineTierPolicy {
    fun eligibleFreeRoutine(routines: List<CommuteRoutine>, selectedId: String?): CommuteRoutine? {
        val lineRoutines = routines.filter { it.type == RoutineType.LINE_DIRECTION }
        return lineRoutines.firstOrNull { it.id == selectedId } ?: lineRoutines.firstOrNull()
    }

    fun canRun(
        routine: CommuteRoutine,
        allRoutines: List<CommuteRoutine>,
        entitlement: EntitlementState,
        selectedId: String?,
    ): Boolean = entitlement.hasPremiumAccess || eligibleFreeRoutine(allRoutines, selectedId)?.id == routine.id
}
