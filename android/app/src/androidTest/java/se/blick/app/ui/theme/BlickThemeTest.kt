package se.blick.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlickThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun explicitLightDisplaysTheCityBackgroundAndUsesTheOpaqueLightCanvasColor() {
        var resolvedBackground = Color.Unspecified
        composeRule.setContent {
            BlickTheme(useDarkTheme = false, systemDarkTheme = true) {
                val background = MaterialTheme.colorScheme.background
                SideEffect { resolvedBackground = background }
                BlickLightBackground { Box(Modifier.testTag("content")) }
            }
        }

        composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG).assertExists()
        composeRule.onAllNodesWithTag(LIGHT_CITY_BACKGROUND_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("content").assertExists()
        composeRule.runOnIdle { assertEquals(Color(0xFFFAF4F3), resolvedBackground) }
    }

    @Test
    fun systemFollowingLightDisplaysTheCityBackground() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = null, systemDarkTheme = false) {
                BlickLightBackground { Box(Modifier.testTag("content")) }
            }
        }

        composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG).assertExists()
        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertDoesNotExist()
    }

    @Test
    fun basicDarkDialogSurfaceMatchesMaterialSurfaceContainerHigh() {
        var dialogSurface = Color.Unspecified
        composeRule.setContent {
            BlickTheme(useDarkTheme = true, systemDarkTheme = false) {
                val resolvedDialogSurface = MaterialTheme.colorScheme.surfaceContainerHigh
                SideEffect { dialogSurface = resolvedDialogSurface }
            }
        }

        composeRule.runOnIdle { assertEquals(BasicDarkDialogSurface, dialogSurface) }
    }

    @Test
    fun explicitDarkDisplaysNoDecorativeThemeBackground() {
        composeRule.setContent {
            BlickTheme(useDarkTheme = true, systemDarkTheme = false) {
                BlickLightBackground { Box(Modifier.testTag("content")) }
            }
        }

        composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("content").assertExists()
    }

    @Test
    fun StockholmNightDisplaysOnlyItsExistingBackground() {
        composeRule.setContent {
            BlickTheme(
                useDarkTheme = false,
                useStockholmNightTheme = true,
                systemDarkTheme = false,
            ) {
                BlickLightBackground { Box(Modifier.testTag("content")) }
            }
        }

        composeRule.onNodeWithTag(LIGHT_CITY_BACKGROUND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertExists()
        composeRule.onAllNodesWithTag(STOCKHOLM_NIGHT_BACKGROUND_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag("content").assertExists()
    }

    @Test
    fun lightPageContainersAreTransparentWithoutChangingTheThemeBackground() {
        var screenContainer = Color.Unspecified
        var themeBackground = Color.Unspecified
        composeRule.setContent {
            BlickTheme(useDarkTheme = false) {
                val resolvedScreenContainer = themedScreenContainerColor()
                val resolvedThemeBackground = MaterialTheme.colorScheme.background
                SideEffect {
                    screenContainer = resolvedScreenContainer
                    themeBackground = resolvedThemeBackground
                }
                BlickLightBackground { Box(Modifier.testTag("content")) }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color.Transparent, screenContainer)
            assertEquals(Color(0xFFFAF4F3), themeBackground)
        }
    }

    @Test
    fun stickySaveActionsAreTransparentOnlyOnTheNormalLightCanvas() {
        var lightVisuals: StickyActionVisuals? = null
        var darkVisuals: StickyActionVisuals? = null
        var stockholmVisuals: StickyActionVisuals? = null
        composeRule.setContent {
            BlickTheme(useDarkTheme = false) {
                val visuals = themedStickyActionVisuals()
                SideEffect { lightVisuals = visuals }
            }
            BlickTheme(useDarkTheme = true) {
                val visuals = themedStickyActionVisuals()
                SideEffect { darkVisuals = visuals }
            }
            BlickTheme(useStockholmNightTheme = true) {
                val visuals = themedStickyActionVisuals()
                SideEffect { stockholmVisuals = visuals }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color.Transparent, lightVisuals?.containerColor)
            assertEquals(0.dp, lightVisuals?.shadowElevation)
            assertEquals(8.dp, darkVisuals?.shadowElevation)
            assertEquals(8.dp, stockholmVisuals?.shadowElevation)
            assertEquals(StockholmNightSurfaces.Card, stockholmVisuals?.containerColor)
        }
    }

}
