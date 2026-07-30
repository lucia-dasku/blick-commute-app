package se.blick.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import se.blick.app.ui.screens.routinecreate.RoutineCreateScreen
import se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen
import se.blick.app.ui.screens.routinelist.RoutineListScreen

@Composable
fun BlickNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.RoutineList.route) {
        composable(Routes.RoutineList.route) {
            RoutineListScreen(
                onAddRoutine = { navController.navigate(Routes.RoutineCreate.route) },
                onOpenRoutine = { routineId -> navController.navigate(Routes.RoutineDetails.routeFor(routineId)) },
            )
        }
        composable(Routes.RoutineCreate.route) {
            RoutineCreateScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.RoutineDetails.route) {
            // routineId is read by RoutineDetailsViewModel from its injected SavedStateHandle
            // (auto-populated by Hilt Navigation Compose from this destination's back stack
            // entry) rather than being threaded through as a screen parameter.
            RoutineDetailsScreen(onBack = { navController.popBackStack() })
        }
    }
}
