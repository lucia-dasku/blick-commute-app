package se.blick.app.ui.screens.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R

/**
 * Instrumented Compose UI test for [AboutScreen] — the one place
 * [se.blick.app.R.string.attribution_text] is actually shown to the user (see
 * docs/api-contract.md §8, Licensing and attribution). No ViewModel/Hilt is involved, so this
 * exercises the composable directly, same convention as `RoutineListScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsTheRequiredAttributionText() {
        composeRule.setContent { AboutScreen(onBack = {}) }

        val attribution = composeRule.activity.getString(R.string.attribution_text)
        composeRule.onNodeWithText(attribution).assertExists()
    }

    @Test
    fun backButtonInvokesOnBack() {
        var backInvoked = false
        composeRule.setContent { AboutScreen(onBack = { backInvoked = true }) }

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).performClick()

        assertEquals(true, backInvoked)
    }
}
