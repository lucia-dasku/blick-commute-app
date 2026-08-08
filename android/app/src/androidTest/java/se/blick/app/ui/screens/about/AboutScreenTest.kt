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
 * Instrumented Compose UI test for [AboutContent] — the stateless composable
 * [AboutScreen] wraps with a real [AboutViewModel] (see that composable's own doc on why this
 * split exists) — the one place [se.blick.app.R.string.attribution_text] is actually shown to
 * the user (see docs/api-contract.md §8, Licensing and attribution). No ViewModel/Hilt is
 * involved, so this exercises the composable directly, same convention as `RoutineListScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsTheRequiredAttributionText() {
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = {}) }

        val attribution = composeRule.activity.getString(R.string.attribution_text)
        composeRule.onNodeWithText(attribution).assertExists()
    }

    @Test
    fun backButtonInvokesOnBack() {
        var backInvoked = false
        composeRule.setContent { AboutContent(onBack = { backInvoked = true }, onLanguageSelected = {}) }

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).performClick()

        assertEquals(true, backInvoked)
    }

    // ---- Language section (see AboutScreen.kt's own LanguageSection doc) ----

    @Test
    fun tappingTheEnglishChipInvokesOnLanguageSelectedWithEn() {
        var selected: String? = null
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = { selected = it }) }

        composeRule.onNodeWithText("English").performClick()

        assertEquals("en", selected)
    }

    @Test
    fun tappingTheSvenskaChipInvokesOnLanguageSelectedWithSv() {
        var selected: String? = null
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = { selected = it }) }

        composeRule.onNodeWithText("Svenska").performClick()

        assertEquals("sv", selected)
    }

    @Test
    fun theLanguageSectionHeadingAndBothChipsAreShown() {
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = {}) }

        val label = composeRule.activity.getString(R.string.settings_language_label)
        composeRule.onNodeWithText(label).assertExists()
        composeRule.onNodeWithText("English").assertExists()
        composeRule.onNodeWithText("Svenska").assertExists()
    }

    // ---- Open-source licences section (see AboutScreen's own doc on this being the very last
    // section) ----

    @Test
    fun showsTheOpenSourceLicencesSectionHeaderAndBody() {
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = {}) }

        val header = composeRule.activity.getString(R.string.about_section_open_source_licences)
        val body = composeRule.activity.getString(R.string.about_open_source_licences_body)
        composeRule.onNodeWithText(header).assertExists()
        composeRule.onNodeWithText(body).assertExists()
    }

    @Test
    fun showsATappableViewOpenSourceLicencesRow() {
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = {}) }

        val action = composeRule.activity.getString(R.string.about_open_source_licences_action)
        // Existence + clickability only, same as the (also untested-for-navigation)
        // Trafiklab.se link above -- not performClick()ed here, since that would fire a real
        // Intent.ACTION_VIEW and hand off to an external browser app rather than staying within
        // this instrumented test.
        composeRule.onNodeWithText(action).assertExists().assertHasClickAction()
    }
}
