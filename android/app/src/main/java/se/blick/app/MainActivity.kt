package se.blick.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import se.blick.app.notification.NotificationIntentCoordinator
import se.blick.app.notification.RoutineNotificationIds
import se.blick.app.ui.navigation.BlickNavHost
import se.blick.app.ui.navigation.Routes
import se.blick.app.ui.theme.BlickTheme

/**
 * Single Activity hosting the whole Compose Navigation graph (see [BlickNavHost]).
 *
 * Handles tapping the ongoing commute notification (see
 * `notification/RoutineNotificationBuilder.contentIntent`), which targets this Activity with
 * a [RoutineNotificationIds.EXTRA_ROUTINE_ID] extra and `FLAG_ACTIVITY_SINGLE_TOP` — so an
 * already-running instance receives [onNewIntent] instead of a second instance being
 * created. [pendingRoutineId] is populated by both [onCreate] (cold start: the routine id
 * arrives via the launching [getIntent]) and [onNewIntent] (warm/hot start), via
 * [NotificationIntentCoordinator.consumeRoutineId] — which both reads AND removes the extra
 * from the underlying `Intent` in one step. That removal matters: `Activity.getIntent()`
 * returns the SAME `Intent` object across an in-process recreation (e.g. a screen rotation),
 * so without removing the extra, a later `onCreate` after such a recreation would observe the
 * exact same routine id again and silently re-navigate the user back to a routine they may
 * have already left — see [NotificationIntentCoordinator]'s own doc and
 * `NotificationIntentCoordinatorTest` for the regression this fixes.
 *
 * [pendingRoutineId] is consumed by a [LaunchedEffect] inside the Compose tree that actually
 * owns the [androidx.navigation.NavHostController] — an Activity method can't call
 * `navController.navigate` directly since the controller only exists inside composition.
 *
 * The navigate call itself pops up to (but keeps) [Routes.RoutineList] before pushing
 * [Routes.RoutineDetails], so `Back` from the reopened details screen always lands on the
 * routine list — matching normal navigation, whether the tap happened while the app was
 * closed, already open on some other screen, or already showing that same details screen
 * (`launchSingleTop` avoids stacking a duplicate details destination in that last case).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingRoutineId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoutineId = NotificationIntentCoordinator.consumeRoutineId(intent)
        setContent {
            BlickTheme {
                val navController = rememberNavController()
                LaunchedEffect(pendingRoutineId) {
                    val routineId = pendingRoutineId ?: return@LaunchedEffect
                    navController.navigate(Routes.RoutineDetails.routeFor(routineId)) {
                        popUpTo(Routes.RoutineList.route)
                        launchSingleTop = true
                    }
                    pendingRoutineId = null
                }
                BlickNavHost(navController = navController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoutineId = NotificationIntentCoordinator.consumeRoutineId(intent)
    }
}
