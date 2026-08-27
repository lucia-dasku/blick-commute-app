package se.blick.app.ui.navigation

sealed class Routes(val route: String) {
    data object RoutineList : Routes("routine-list")
    data object RoutineCreate : Routes("routine-create")
    data object About : Routes("about")
    data object PrivacyPolicy : Routes("privacy-policy")
    data object DataAttribution : Routes("data-attribution")
    data object OpenSourceLicences : Routes("open-source-licences")
    data object Premium : Routes("premium")
    data object OneTimeEventCreate : Routes("one-time-event-create")
    data object OneTimeEvents : Routes("one-time-events")

    data object OneTimeEventEdit : Routes("one-time-event-edit/{eventId}") {
        fun routeFor(eventId: String) = "one-time-event-edit/$eventId"
    }

    data object OneTimeEventDetails : Routes("one-time-event-details/{eventId}") {
        fun routeFor(eventId: String) = "one-time-event-details/$eventId"
    }

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
