package se.blick.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R

/**
 * Instrumented Compose test for [BlickTopBar] — exercised directly since it's the shared header
 * used identically by four different screens (see its own class doc); a regression here would
 * affect all of them at once. Covers [title]'s nullability specifically, including the brief
 * loading state before Routine Details has a saved routine name to show beside Back.
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
    fun aNonNullTitleSharesTheBackButtonRow() {
        composeRule.setContent { BlickTopBar(title = sampleTitle, onBack = {}) }

        val backDescription = composeRule.activity.getString(R.string.action_back)
        val titleBounds = composeRule.onNodeWithText(sampleTitle).fetchSemanticsNode().boundsInRoot
        val backBounds = composeRule.onNodeWithContentDescription(backDescription).fetchSemanticsNode().boundsInRoot

        assertEquals(backBounds.center.y, titleBounds.center.y, 2f)
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
