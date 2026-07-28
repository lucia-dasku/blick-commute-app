package se.blick.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import se.blick.app.ui.screens.routinecreate.RoutineCreateScreen
import se.blick.app.ui.screens.routineedit.RoutineEditScreen
import se.blick.app.ui.screens.routinelist.RoutineListScreen

@Composable
fun BlickNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.RoutineList.route) {
        composable(Routes.RoutineList.route) {
            RoutineListScreen(
                onAddRoutine = { navController.navigate(Routes.RoutineCreate.route) },
                onOpenRoutine = { routineId -> navController.navigate(Routes.RoutineEdit.routeFor(routineId)) },
            )
        }
        composable(Routes.RoutineCreate.route) {
            RoutineCreateScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.RoutineEdit.route) { backStackEntry ->
            val routineId = backStackEntry.arguments?.getString(Routes.RoutineEdit.ARG_ROUTINE_ID).orEmpty()
            RoutineEditScreen(routineId = routineId, onDone = { navController.popBackStack() })
        }
    }
}
