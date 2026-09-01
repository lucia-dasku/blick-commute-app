package se.blick.app.ads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.navigation.Routes
import se.blick.app.ui.theme.BlickLightBackground
import se.blick.app.ui.theme.BlickTheme
import se.blick.app.ui.theme.LIGHT_CITY_BACKGROUND_TAG

@RunWith(AndroidJUnit4::class)
class BannerAwareContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun premiumTransitionImmediatelyRemovesBannerHostWithoutGoogleNetworking() {
        var entitlement by mutableStateOf<EntitlementState>(EntitlementState.Free)
        composeRule.setContent {
            BannerAwareContent(
                bannerEligible = shouldRequestBanner(
                    entitlement = entitlement,
                    route = Routes.RoutineList.route,
                    canRequestAds = true,
                ),
                content = {},
                banner = { Box(Modifier.testTag(BANNER_CONTAINER_TEST_TAG)) },
            )
        }

        composeRule.onNodeWithTag(BANNER_CONTAINER_TEST_TAG).assertExists()

        composeRule.runOnUiThread { entitlement = EntitlementState.Premium }

        composeRule.onNodeWithTag(BANNER_CONTAINER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun eligibleBannerIsBelowTheContentAndLightSkyline() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = false) {
                BannerAwareContent(
                    bannerEligible = true,
                    content = {
                        BlickLightBackground {
                            Box(Modifier.fillMaxSize().testTag("navigation-content"))
                        }
                    },
                    banner = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag(BANNER_CONTAINER_TEST_TAG),
                        )
                    },
                )
            }
        }

        val contentBounds = composeRule.onNodeWithTag(BANNER_CONTENT_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val bannerBounds = composeRule.onNodeWithTag(BANNER_CONTAINER_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val skylineBounds = composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG)
            .fetchSemanticsNode().boundsInRoot

        assertEquals(contentBounds.bottom, bannerBounds.top, 1f)
        assertEquals(contentBounds.bottom, skylineBounds.bottom, 1f)
    }

    @Test
    fun absentOrUnloadedBannerLeavesNoGapBelowTheLightContent() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = false) {
                BannerAwareContent(
                    // Mirrors an eligible AdBanner before it has loaded: the banner slot is
                    // composed but emits no measurable UI.
                    bannerEligible = true,
                    content = {
                        BlickLightBackground {
                            Box(Modifier.fillMaxSize().testTag("navigation-content"))
                        }
                    },
                    banner = {},
                )
            }
        }

        val layoutBounds = composeRule.onNodeWithTag(BANNER_AWARE_LAYOUT_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val contentBounds = composeRule.onNodeWithTag(BANNER_CONTENT_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val skylineBounds = composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG)
            .fetchSemanticsNode().boundsInRoot

        assertEquals(layoutBounds.bottom, contentBounds.bottom, 1f)
        assertEquals(contentBounds.bottom, skylineBounds.bottom, 1f)
        composeRule.onNodeWithTag(BANNER_CONTAINER_TEST_TAG).assertDoesNotExist()
    }
}
