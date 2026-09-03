package se.blick.app.ui.screens.premium

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class PremiumScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun offerShowsUpdatedBenefitsAndPurchaseTerms() {
        setPremiumContent(localizedPrice = null)

        composeRule.onNodeWithText(string(R.string.premium_heading)).assertExists()
        composeRule.onNodeWithText(string(R.string.premium_supporting_text)).assertExists()
        composeRule.onAllNodesWithTag("premium-benefit-check", useUnmergedTree = true).assertCountEquals(6)
        val benefitPositions = listOf(
            R.string.premium_feature_multiple,
            R.string.premium_feature_destinations,
            R.string.premium_feature_one_time_event,
            R.string.premium_feature_event_recommendations,
            R.string.premium_feature_stockholm_night,
            R.string.premium_feature_ad_free,
        ).map { resourceId ->
            composeRule.onNodeWithText("• ${string(resourceId)}").assertDoesNotExist()
            composeRule.onNodeWithText(string(resourceId)).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(benefitPositions.zipWithNext().all { (previous, next) -> previous < next })

        val purchaseTerms = string(R.string.premium_purchase_terms)
        composeRule.onNodeWithText(purchaseTerms).assertExists()
        composeRule.onNodeWithText("• $purchaseTerms").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.premium_restore)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun purchaseButtonUsesGooglePlayFormattedPriceWhenAvailable() {
        val localizedPrice = "49 kr"
        setPremiumContent(localizedPrice = localizedPrice)

        composeRule
            .onNodeWithText(string(R.string.premium_purchase_with_price, localizedPrice))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun purchaseButtonFallsBackAndPurchaseActionsRemainConnected() {
        var purchased = false
        var restored = false
        setPremiumContent(
            localizedPrice = null,
            onPurchase = { purchased = true },
            onRestore = { restored = true },
        )

        composeRule
            .onNodeWithText(string(R.string.premium_purchase))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText(string(R.string.premium_restore))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(purchased)
            assertTrue(restored)
        }
    }

    private fun setPremiumContent(
        localizedPrice: String?,
        onPurchase: () -> Unit = {},
        onRestore: () -> Unit = {},
    ) {
        composeRule.setContent {
            BlickTheme {
                PremiumContent(
                    state = PremiumUiState(
                        entitlement = EntitlementState.Free,
                        localizedPrice = localizedPrice,
                    ),
                    canLaunchPurchase = true,
                    onPurchase = onPurchase,
                    onRestore = onRestore,
                    onToggleDebugPremium = {},
                )
            }
        }
    }

    private fun string(resourceId: Int, vararg formatArgs: Any): String =
        composeRule.activity.getString(resourceId, *formatArgs)
}
