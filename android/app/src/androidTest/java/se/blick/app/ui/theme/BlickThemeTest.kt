package se.blick.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlickThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun StockholmNightThemeDisplaysTheSuppliedBackgroundAsset() {
        composeRule.setContent {
            BlickTheme(useStockholmNightTheme = true) {
                Box(Modifier.testTag("content"))
            }
        }

        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertExists()
        composeRule.onNodeWithTag("content").assertExists()
    }

    @Test
    fun regularThemeDoesNotDisplayTheStockholmBackground() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = true) {
                Box(Modifier.testTag("content"))
            }
        }

        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("content").assertExists()
    }
}
