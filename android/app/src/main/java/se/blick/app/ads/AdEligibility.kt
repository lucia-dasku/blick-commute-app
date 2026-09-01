package se.blick.app.ads

import se.blick.app.billing.EntitlementState
import se.blick.app.ui.navigation.Routes

/** Advertising is limited to states that do not have verified or fail-soft Premium access. */
fun isBannerEntitlementEligible(entitlement: EntitlementState): Boolean = when (entitlement) {
    EntitlementState.Loading,
    EntitlementState.Premium,
    -> false

    EntitlementState.Free,
    EntitlementState.Pending,
    -> true

    is EntitlementState.TemporarilyUnavailable -> !entitlement.lastVerifiedPremium
}

/** Navigation remains the only source of truth for which foreground surfaces may contain ads. */
fun shouldShowBannerForRoute(route: String?): Boolean = when (route) {
    Routes.RoutineList.route,
    Routes.RoutineDetails.route,
    -> true

    else -> false
}

fun shouldRequestBanner(
    entitlement: EntitlementState,
    route: String?,
    canRequestAds: Boolean,
): Boolean = isBannerEntitlementEligible(entitlement) &&
    shouldShowBannerForRoute(route) &&
    canRequestAds

fun shouldDisplayBanner(
    entitlement: EntitlementState,
    route: String?,
    canRequestAds: Boolean,
    adsInitialized: Boolean,
    adLoaded: Boolean,
): Boolean = shouldRequestBanner(entitlement, route, canRequestAds) &&
    adsInitialized &&
    adLoaded
