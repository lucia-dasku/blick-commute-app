package se.blick.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import se.blick.app.ui.screens.about.AboutScreen
import se.blick.app.ui.screens.premium.PremiumScreen
import se.blick.app.ui.screens.routinecreate.RoutineCreateScreen
import se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen
import se.blick.app.ui.screens.routinedetails.RoutineDetailsViewModel
import se.blick.app.ui.screens.routinelist.RoutineListScreen

/** Key for the previous back stack entry's `SavedStateHandle` result signal, set when
 * [Routes.RoutineEdit] finishes successfully and consumed by [Routes.RoutineDetails] — see
 * that composable below for why a plain nav-result flag (rather than a shared ViewModel or a
 * periodic refresh) is the right way to tell an already-alive Details screen to re-check its
 * routine after an edit. */
private const val ROUTE_RESULT_ROUTINE_EDITED = "routineEdited"

@Composable
fun BlickNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.RoutineList.route) {
        composable(Routes.RoutineList.route) {
            RoutineListScreen(
                onAddRoutine = { navController.navigate(Routes.RoutineCreate.route) },
                onOpenPremium = { navController.navigate(Routes.Premium.route) },
                onOpenRoutine = { routineId -> navController.navigate(Routes.RoutineDetails.routeFor(routineId)) },
                onOpenAbout = { navController.navigate(Routes.About.route) },
            )
        }
        composable(Routes.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Premium.route) {
            PremiumScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RoutineCreate.route) {
            RoutineCreateScreen(
                onDone = { navController.popBackStack() },
                onOpenPremium = { navController.navigate(Routes.Premium.route) },
            )
        }
        composable(Routes.RoutineEdit.route) {
            // Same screen/ViewModel as RoutineCreate (see RoutineCreateViewModel's edit-mode
            // support) — only the route differs, so a routineId can be supplied via
            // navigation. On a successful save, signal the Details screen being returned to
            // (via its own SavedStateHandle) rather than trying to reach its ViewModel
            // directly — see ROUTE_RESULT_ROUTINE_EDITED's doc.
            RoutineCreateScreen(
                onDone = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ROUTE_RESULT_ROUTINE_EDITED, true)
                    navController.popBackStack()
                },
                onOpenPremium = { navController.navigate(Routes.Premium.route) },
            )
        }
        composable(Routes.RoutineDetails.route) { backStackEntry ->
            // routineId is read by RoutineDetailsViewModel from its injected SavedStateHandle
            // (auto-populated by Hilt Navigation Compose from this destination's back stack
            // entry) rather than being threaded through as a screen parameter.
            val viewModel: RoutineDetailsViewModel = hiltViewModel()

            // This ViewModel instance survives being covered by the Edit screen (it belongs
            // to this still-alive back stack entry), so its own init{} won't re-run on
            // return — reload() is how it picks up a successful edit's changes instead of
            // requiring a periodic background refresh.
            val editedFlow = backStackEntry.savedStateHandle
                .getStateFlow(ROUTE_RESULT_ROUTINE_EDITED, false)
            val edited by editedFlow.collectAsStateWithLifecycle()
            LaunchedEffect(edited) {
                if (edited) {
                    viewModel.reload()
                    backStackEntry.savedStateHandle[ROUTE_RESULT_ROUTINE_EDITED] = false
                }
            }

            RoutineDetailsScreen(
                onBack = { navController.popBackStack() },
                onEdit = { routineId -> navController.navigate(Routes.RoutineEdit.routeFor(routineId)) },
                onDeleted = {
                    navController.navigate(Routes.RoutineList.route) {
                        popUpTo(Routes.RoutineList.route) { inclusive = true }
                    }
                },
                viewModel = viewModel,
            )
        }
    }
}
