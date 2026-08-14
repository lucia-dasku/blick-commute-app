package se.blick.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R

/**
 * Instrumented Compose test for [BlickTopBar] — exercised directly since it's the shared header
 * used identically by four different screens (see its own class doc); a regression here would
 * affect all of them at once. Covers [title]'s nullability specifically —
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen] is the one call site that now
 * passes `null`, since that screen's own journeys/departures heading already identifies it.
 */
@RunWith(AndroidJUnit4::class)
class BlickTopBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleTitle = "Sample Title"

    @Test
    fun aNonNullTitleIsShown() {
        composeRule.setContent { BlickTopBar(title = sampleTitle) }

        composeRule.onNodeWithText(sampleTitle).assertIsDisplayed()
    }

    @Test
    fun aNullTitleShowsNoTitleTextAtAll() {
        composeRule.setContent { BlickTopBar(title = null) }

        composeRule.onNodeWithText(sampleTitle).assertDoesNotExist()
    }

    @Test
    fun aNullTitleStillShowsAWorkingBackButtonWhenOnBackIsSupplied() {
        var backPressed = false
        composeRule.setContent { BlickTopBar(title = null, onBack = { backPressed = true }) }

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).assertIsDisplayed().performClick()

        assertTrue("expected the back button to still invoke onBack with a null title", backPressed)
    }

    @Test
    fun noOnBackOmitsTheBackButtonRegardlessOfTitle() {
        composeRule.setContent { BlickTopBar(title = null) }

        val backDescription = composeRule.activity.getString(R.string.action_back)
        composeRule.onNodeWithContentDescription(backDescription).assertDoesNotExist()
    }
}
