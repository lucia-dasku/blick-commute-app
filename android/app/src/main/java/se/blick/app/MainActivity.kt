package se.blick.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import se.blick.app.ads.AdBanner
import se.blick.app.ads.AdConsentManager
import se.blick.app.ads.AdMobInitializer
import se.blick.app.ads.BannerAwareContent
import se.blick.app.ads.isBannerEntitlementEligible
import se.blick.app.ads.shouldRequestBanner
import se.blick.app.ads.shouldShowBannerForRoute
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.local.datastore.AppSettings
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.locale.withAppLocale
import se.blick.app.notification.NotificationIntentCoordinator
import se.blick.app.notification.RoutineNotificationIds
import se.blick.app.domain.model.ActiveCommuteSource
import se.blick.app.ui.navigation.BlickNavHost
import se.blick.app.ui.navigation.Routes
import se.blick.app.ui.theme.BlickTheme
import se.blick.app.ui.theme.BlickLightBackground
import se.blick.app.ui.theme.shouldUseStockholmNightTheme
import se.blick.app.scheduling.OneTimeEventReminderNavigation
import javax.inject.Inject

/**
 * Single Activity hosting the whole Compose Navigation graph (see [BlickNavHost]).
 *
 * Handles tapping the ongoing commute notification (see
 * `notification/RoutineNotificationBuilder.contentIntent`), which targets this Activity with
 * explicit active-commute source type/id and `FLAG_ACTIVITY_SINGLE_TOP` — so an
 * already-running instance receives [onNewIntent] instead of a second instance being
 * created. The pending Routine/Event destination is populated by both [onCreate] (cold start)
 * and [onNewIntent] (warm/hot start), via
 * [NotificationIntentCoordinator.consumeActiveCommuteSource] — which reads AND removes the extras
 * from the underlying `Intent` in one step. That removal matters: `Activity.getIntent()`
 * returns the SAME `Intent` object across an in-process recreation (e.g. a screen rotation),
 * so without removing the extra, a later `onCreate` after such a recreation would observe the
 * exact same source again and silently re-navigate the user back to details they may
 * have already left — see [NotificationIntentCoordinator]'s own doc and
 * `NotificationIntentCoordinatorTest` for the regression this fixes.
 *
 * The pending destination is consumed by a [LaunchedEffect] inside the Compose tree that actually
 * owns the [androidx.navigation.NavHostController] — an Activity method can't call
 * `navController.navigate` directly since the controller only exists inside composition.
 *
 * The navigate call itself pops up to (but keeps) [Routes.RoutineList] before pushing
 * [Routes.RoutineDetails], so `Back` from the reopened details screen always lands on the
 * routine list — matching normal navigation, whether the tap happened while the app was
 * closed, already open on some other screen, or already showing that same details screen
 * (`launchSingleTop` avoids stacking a duplicate details destination in that last case).
 *
 * [AppCompatActivity], not a plain `ComponentActivity` — required for Blick's own selected
 * app language (English/Svenska, see [se.blick.app.locale.withAppLocale]) to actually apply to
 * this Activity's own resources and to auto-recreate it on a language change on Android 8-12;
 * on Android 13+ the platform does both natively regardless of Activity type, so this only
 * matters pre-13. [se.blick.app.ui.theme.BlickTheme] (Compose) still owns every actual visual
 * style — `themes.xml`'s `Theme.Blick` merely needed to become AppCompat-descended (still
 * `NoActionBar`, still no visible change) to satisfy `AppCompatActivity`'s own theme
 * requirement.
 *
 * [attachBaseContext] rewraps `newBase` through [withAppLocale] — device-tested regression fix:
 * with no explicit Blick choice and an ORDERED system locale list whose first entry Blick has
 * no resources for (e.g. Lithuanian-then-Swedish), this Activity's own ambient
 * [android.content.res.Resources] resolved plain [androidx.compose.ui.res.stringResource] calls
 * against the RAW multi-locale system [android.content.res.Configuration] — which, verified
 * directly on a real Android 14 device, does NOT skip the unsupported first entry the way
 * [se.blick.app.locale.effectiveBlickLocale]'s own doc (correctly) documents ordinary Android
 * resource resolution as doing; weekday/time formatting (already routed through
 * [se.blick.app.locale.currentBlickLocale] in the screens that need it) rendered correctly
 * Swedish while every plain string resource on the very same screen stayed English. This is the
 * SAME [withAppLocale] the notification/widget already rely on for a background context, so
 * every [androidx.compose.ui.res.stringResource] call anywhere in this Activity now agrees with
 * it too, with no per-screen change needed. Deliberately NOT done by wrapping
 * `androidx.compose.ui.platform.LocalContext` inside `setContent` instead (tried first, reverted
 * after a real crash): [androidx.hilt.navigation.compose.hiltViewModel] walks the Context chain
 * looking for the actual hosting Activity, and the `Context` [Context.createConfigurationContext]
 * returns is a standalone `ContextImpl` that breaks that chain —
 * `IllegalStateException: Expected an activity context for creating a HiltViewModelFactory`.
 * Overriding [attachBaseContext] instead keeps `LocalContext.current` as this exact Activity
 * instance (Hilt stays happy) while still correctly localizing everything the Activity's own
 * [android.content.Context.getResources] resolves. Confirmed safe against
 * [androidx.appcompat.app.AppCompatDelegate]'s own base-context wrapping (verified directly from
 * `AppCompatDelegateImpl`'s source): on API 33+ — this app's `minSdk` aside, still worth stating
 * precisely — `calculateApplicationLocales` returns `null` unconditionally, so AppCompat never
 * touches locale in its own `attachBaseContext2` there at all; below 33, it only does so when an
 * explicit choice already exists, in which case it recomputes the same explicit locale
 * [withAppLocale] already resolved to, never something that disagrees with it.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var appSettingsDataStore: AppSettingsDataStore
    @Inject lateinit var premiumEntitlementRepository: PremiumEntitlementRepository
    @Inject lateinit var adConsentManager: AdConsentManager
    @Inject lateinit var adMobInitializer: AdMobInitializer

    private var pendingRoutineId by mutableStateOf<String?>(null)
    private var pendingOneTimeEventId by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeNotificationNavigation(intent)
        // Refresh UMP information on launch, but the manager presents a required form only when
        // the current destination and entitlement make foreground advertising relevant.
        adConsentManager.requestConsentInfoUpdate(this)
        setContent {
            val appSettings by appSettingsDataStore.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val entitlement by premiumEntitlementRepository.entitlement.collectAsStateWithLifecycle()
            val consentState by adConsentManager.state.collectAsStateWithLifecycle()
            BlickTheme(
                useDarkTheme = appSettings.useDarkTheme,
                useStockholmNightTheme = shouldUseStockholmNightTheme(
                    requested = appSettings.useStockholmNightTheme,
                    hasPremiumAccess = entitlement.hasPremiumAccess,
                ),
            ) {
                val navController = rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route
                val advertisingRelevant = isBannerEntitlementEligible(entitlement) &&
                    shouldShowBannerForRoute(currentRoute)

                LaunchedEffect(advertisingRelevant) {
                    adConsentManager.setAdvertisingRelevant(
                        activity = this@MainActivity,
                        relevant = advertisingRelevant,
                    )
                }
                LaunchedEffect(pendingRoutineId) {
                    val routineId = pendingRoutineId ?: return@LaunchedEffect
                    navController.navigate(Routes.RoutineDetails.routeFor(routineId)) {
                        popUpTo(Routes.RoutineList.route)
                        launchSingleTop = true
                    }
                    pendingRoutineId = null
                }
                LaunchedEffect(pendingOneTimeEventId) {
                    val eventId = pendingOneTimeEventId ?: return@LaunchedEffect
                    navController.navigate(Routes.OneTimeEventDetails.routeFor(eventId)) {
                        popUpTo(Routes.RoutineList.route)
                        launchSingleTop = true
                    }
                    pendingOneTimeEventId = null
                }
                BannerAwareContent(
                    bannerEligible = shouldRequestBanner(
                        entitlement = entitlement,
                        route = currentRoute,
                        canRequestAds = consentState.canRequestAds,
                    ),
                    content = {
                        BlickLightBackground {
                            BlickNavHost(
                                navController = navController,
                                privacyOptionsRequired = consentState.privacyOptionsRequired,
                                onOpenPrivacyOptions = {
                                    adConsentManager.showPrivacyOptions(this@MainActivity)
                                },
                            )
                        }
                    },
                    banner = {
                        AdBanner(
                            adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
                            consentRevision = consentState.revision,
                            initializer = adMobInitializer,
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationNavigation(intent)
    }

    private fun consumeNotificationNavigation(intent: Intent) {
        when (val source = NotificationIntentCoordinator.consumeActiveCommuteSource(intent)) {
            is ActiveCommuteSource.Routine -> pendingRoutineId = source.id
            is ActiveCommuteSource.OneTimeEvent -> pendingOneTimeEventId = source.id
            null -> {
                // Backward compatibility for an already-posted notification created by an
                // older app version, plus the separate event-day reminder notification.
                pendingRoutineId = NotificationIntentCoordinator.consumeRoutineId(intent)
                pendingOneTimeEventId = consumeOneTimeEventId(intent)
            }
        }
    }

    private fun consumeOneTimeEventId(intent: Intent): String? =
        OneTimeEventReminderNavigation.consumeEventId(intent)
}
