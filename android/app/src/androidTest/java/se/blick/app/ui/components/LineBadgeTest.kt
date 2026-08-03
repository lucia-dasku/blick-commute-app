package se.blick.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.domain.model.TransportMode

/**
 * Instrumented Compose UI test for [LineBadge] — the shared, colored line-number badge reused
 * throughout the app (route selection, Routine Details, departure rows, the routine list) and,
 * separately, on the home-screen widget (see [se.blick.app.widget.BlickRoutineWidget]'s own
 * Glance-rendered badge, which draws from the exact same [se.blick.app.widget.LineBadgeColorMapping]/
 * `toBadgeColor()` values — see that mapping's own `LineBadgeColorMappingTest` for the exhaustive,
 * plain-JVM-tested color-per-line-family correctness this composable relies on rather than
 * re-testing). This file proves the Compose-level integration: the badge always renders its text
 * (colored or grey-fallback, never uncolored/hidden/blank), for every [TransportMode] this
 * mapping distinguishes, and always with a real, non-zero measured size.
 */
@RunWith(AndroidJUnit4::class)
class LineBadgeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLineNumberTextIsAlwaysDisplayed_forAColoredMetroLine() {
        composeRule.setContent {
            LineBadge(lineDesignation = "14", transportMode = TransportMode.METRO)
        }
        composeRule.onNodeWithText("14").assertIsDisplayed()
    }

    @Test
    fun theLineNumberTextIsAlwaysDisplayed_forAColoredPendeltagLine() {
        composeRule.setContent {
            LineBadge(lineDesignation = "42X", transportMode = TransportMode.TRAIN)
        }
        composeRule.onNodeWithText("42X").assertIsDisplayed()
    }

    @Test
    fun theLineNumberTextIsAlwaysDisplayed_forAnUnmappedBusLine_neverHidden() {
        // No BUS line is ever colored by LineBadgeColorMapping -- resolves to the grey
        // fallback, but the badge itself (and its text) must still render, never disappear.
        composeRule.setContent {
            LineBadge(lineDesignation = "705", transportMode = TransportMode.BUS)
        }
        composeRule.onNodeWithText("705").assertIsDisplayed()
    }

    @Test
    fun aBusSharingAMetroLineNumberStillRendersItsOwnText() {
        // "14" is metro-red, but this composable must faithfully pass BOTH mode and line
        // through to the mapping (see LineBadgeColorMapping.colorFor's own doc) rather than
        // coloring by number alone -- this doesn't assert the resulting color (that's
        // LineBadgeColorMappingTest's job), only that rendering itself doesn't break or hide
        // the text for this specific disambiguation case.
        composeRule.setContent {
            LineBadge(lineDesignation = "14", transportMode = TransportMode.BUS)
        }
        composeRule.onNodeWithText("14").assertIsDisplayed()
    }

    @Test
    fun theBadgeIsLaidOutWithARealNonZeroSize() {
        composeRule.setContent {
            Box {
                LineBadge(
                    lineDesignation = "18",
                    transportMode = TransportMode.METRO,
                    modifier = Modifier.testTag("badge"),
                )
            }
        }
        val bounds = composeRule.onNodeWithTag("badge").fetchSemanticsNode().boundsInRoot
        assertTrue("expected the badge to have a real measured size, got $bounds", bounds.width > 0f && bounds.height > 0f)
    }

    @Test
    fun theLineNumberTextIsHorizontallyCenteredWithinTheBadge() {
        composeRule.setContent {
            Box {
                LineBadge(
                    lineDesignation = "1",
                    transportMode = TransportMode.METRO,
                    modifier = Modifier.testTag("badge"),
                )
            }
        }
        val badgeBounds = composeRule.onNodeWithTag("badge").fetchSemanticsNode().boundsInRoot
        val textBounds = composeRule.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val badgeCenterX = (badgeBounds.left + badgeBounds.right) / 2
        val textCenterX = (textBounds.left + textBounds.right) / 2
        assertTrue(
            "expected the line number text centered within the badge (badge center $badgeCenterX, text center $textCenterX)",
            kotlin.math.abs(badgeCenterX - textCenterX) < 2f,
        )
    }
}
