package se.blick.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class BlickHomeHeaderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun headerMatchesTheApprovedBrandLockupGeometry() {
        composeRule.setContent {
            BlickTheme(useStockholmNightTheme = true) {
                BlickHomeHeader(useStockholmNightBranding = true, onOpenAbout = {})
            }
        }

        val density = composeRule.activity.resources.displayMetrics.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val logo = composeRule.onNodeWithTag(BLICK_HOME_LOGO_TAG).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithTag(BLICK_HOME_TITLE_TAG).fetchSemanticsNode().boundsInRoot
        val subtitle = composeRule.onNodeWithTag(BLICK_HOME_SUBTITLE_TAG).fetchSemanticsNode().boundsInRoot
        val settingsCircle = composeRule
            .onNodeWithTag(BLICK_HOME_SETTINGS_CIRCLE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        fun pixelsToDp(pixels: Float): Float = pixels / density

        assertEquals(24f, pixelsToDp(logo.left - root.left), 1f)
        assertEquals(37f, pixelsToDp(logo.width), 1f)
        assertEquals(48f, pixelsToDp(logo.height), 1f)
        assertEquals(9f, pixelsToDp(title.left - logo.right), 1f)
        assertEquals(title.left, subtitle.left, density)
        assertEquals(42f, pixelsToDp(settingsCircle.width), 1f)
        assertEquals(42f, pixelsToDp(settingsCircle.height), 1f)
        assertEquals(24f, pixelsToDp(root.right - settingsCircle.right), 1f)
        assertEquals(logo.center.y, settingsCircle.center.y, density)
    }

    @Test
    fun headerUsesExactTextAndKeepsSettingsBehavior() {
        var settingsOpened = false
        composeRule.setContent {
            BlickTheme(useStockholmNightTheme = true) {
                BlickHomeHeader(
                    useStockholmNightBranding = true,
                    onOpenAbout = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.brand_home_title),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.brand_stockholm_night_subtitle),
        ).assertExists()
        composeRule.onNodeWithTag(BLICK_HOME_SETTINGS_TAG).performClick()

        assertEquals(true, settingsOpened)
    }

    @Test
    fun regularThemeUsesAlwaysOnTimeInsteadOfThePremiumSubtitle() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = false) {
                BlickHomeHeader(useStockholmNightBranding = false, onOpenAbout = {})
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.brand_home_subtitle),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.brand_stockholm_night_subtitle),
        ).assertDoesNotExist()
    }
}
