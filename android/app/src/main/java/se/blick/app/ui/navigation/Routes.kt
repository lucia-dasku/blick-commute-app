package se.blick.app.ui.navigation

sealed class Routes(val route: String) {
    data object RoutineList : Routes("routine-list")
    data object RoutineCreate : Routes("routine-create")

    /** Same screen/ViewModel as [RoutineCreate] (see RoutineCreateViewModel's edit-mode
     * support) — a distinct route only so a routineId can be supplied via navigation and
     * picked up through SavedStateHandle. */
    data object RoutineEdit : Routes("routine-edit/{routineId}") {
        const val ARG_ROUTINE_ID = "routineId"
        fun routeFor(routineId: String) = "routine-edit/$routineId"
    }

    data object RoutineDetails : Routes("routine-details/{routineId}") {
        const val ARG_ROUTINE_ID = "routineId"
        fun routeFor(routineId: String) = "routine-details/$routineId"
    }
}
