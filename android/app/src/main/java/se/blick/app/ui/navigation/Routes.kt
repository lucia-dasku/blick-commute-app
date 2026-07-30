package se.blick.app.ui.navigation

sealed class Routes(val route: String) {
    data object RoutineList : Routes("routine-list")
    data object RoutineCreate : Routes("routine-create")

    data object RoutineDetails : Routes("routine-details/{routineId}") {
        const val ARG_ROUTINE_ID = "routineId"
        fun routeFor(routineId: String) = "routine-details/$routineId"
    }
}
