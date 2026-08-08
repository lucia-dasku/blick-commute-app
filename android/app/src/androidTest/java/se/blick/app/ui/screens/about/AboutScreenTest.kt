package se.blick.app.ui.screens.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
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

    // ---- Open-source licences section (see AboutScreen's own doc on this being the very last
    // section) ----

    @Test
    fun showsTheOpenSourceLicencesSectionHeaderAndBody() {
        composeRule.setContent { AboutScreen(onBack = {}) }

        val header = composeRule.activity.getString(R.string.about_section_open_source_licences)
        val body = composeRule.activity.getString(R.string.about_open_source_licences_body)
        composeRule.onNodeWithText(header).assertExists()
        composeRule.onNodeWithText(body).assertExists()
    }

    @Test
    fun showsATappableViewOpenSourceLicencesRow() {
        composeRule.setContent { AboutScreen(onBack = {}) }

        val action = composeRule.activity.getString(R.string.about_open_source_licences_action)
        // Existence + clickability only, same as the (also untested-for-navigation)
        // Trafiklab.se link above -- not performClick()ed here, since that would fire a real
        // Intent.ACTION_VIEW and hand off to an external browser app rather than staying within
        // this instrumented test.
        composeRule.onNodeWithText(action).assertExists().assertHasClickAction()
    }
}
