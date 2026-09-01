package se.blick.app.ads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.navigation.Routes

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
}
