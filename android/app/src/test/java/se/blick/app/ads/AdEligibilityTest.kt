package se.blick.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.navigation.Routes

class AdEligibilityTest {

    @Test
    fun entitlementPolicyMatchesPremiumAuthority() {
        assertFalse(isBannerEntitlementEligible(EntitlementState.Loading))
        assertTrue(isBannerEntitlementEligible(EntitlementState.Free))
        assertFalse(isBannerEntitlementEligible(EntitlementState.Premium))
        assertTrue(isBannerEntitlementEligible(EntitlementState.Pending))
        assertFalse(
            isBannerEntitlementEligible(
                EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = true),
            ),
        )
        assertTrue(
            isBannerEntitlementEligible(
                EntitlementState.TemporarilyUnavailable(lastVerifiedPremium = false),
            ),
        )
    }

    @Test
    fun routePolicyAllowsOnlyRoutineListAndRoutineDetails() {
        assertTrue(shouldShowBannerForRoute(Routes.RoutineList.route))
        assertTrue(shouldShowBannerForRoute(Routes.RoutineDetails.route))

        listOf(
            Routes.RoutineCreate.route,
            Routes.RoutineEdit.route,
            Routes.Premium.route,
            Routes.About.route,
            Routes.PrivacyPolicy.route,
            Routes.DataAttribution.route,
            Routes.OpenSourceLicences.route,
            Routes.OneTimeEventCreate.route,
            Routes.OneTimeEventEdit.route,
            Routes.OneTimeEventDetails.route,
            Routes.OneTimeEvents.route,
            null,
        ).forEach { route ->
            assertFalse("Unexpectedly eligible route: $route", shouldShowBannerForRoute(route))
        }
    }

    @Test
    fun displayRequiresEveryEligibilityAndLoadCondition() {
        assertTrue(
            shouldDisplayBanner(
                entitlement = EntitlementState.Free,
                route = Routes.RoutineList.route,
                canRequestAds = true,
                adsInitialized = true,
                adLoaded = true,
            ),
        )

        assertFalse(
            shouldDisplayBanner(EntitlementState.Premium, Routes.RoutineList.route, true, true, true),
        )
        assertFalse(
            shouldDisplayBanner(EntitlementState.Free, Routes.About.route, true, true, true),
        )
        assertFalse(
            shouldDisplayBanner(EntitlementState.Free, Routes.RoutineList.route, false, true, true),
        )
        assertFalse(
            shouldDisplayBanner(EntitlementState.Free, Routes.RoutineList.route, true, false, true),
        )
        assertFalse(
            shouldDisplayBanner(EntitlementState.Free, Routes.RoutineList.route, true, true, false),
        )
    }
}
