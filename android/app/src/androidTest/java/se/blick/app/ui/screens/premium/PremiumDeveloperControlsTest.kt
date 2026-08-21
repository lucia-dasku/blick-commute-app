package se.blick.app.ui.screens.premium

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class PremiumDeveloperControlsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun debugOverrideControlIsVisibleAndInvokesItsCallback() {
        var toggled = false
        composeRule.setContent {
            BlickTheme {
                PremiumDeveloperOverrideControls(
                    available = true,
                    enabled = false,
                    onToggle = { toggled = true },
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.premium_debug_enable))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(toggled) }
    }

    @Test
    fun debugOverrideActiveMessageIsVisible() {
        composeRule.setContent {
            BlickTheme { PremiumActiveStatus(debugOverrideEnabled = true) }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.premium_debug_active))
            .assertIsDisplayed()
    }
}
