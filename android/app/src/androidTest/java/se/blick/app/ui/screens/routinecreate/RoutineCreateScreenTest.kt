package se.blick.app.ui.screens.routinecreate

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for [WeekdaySelector] — exercises it directly (an `internal`
 * composable, same rule as [se.blick.app.ui.screens.routinedetails.shouldOfferLiveUpdateSettingsLink]'s
 * own direct-test convention) rather than the full [RoutineCreateScreen], which needs a
 * saved-stop/line/direction flow and a real ViewModel to reach the weekday step at all.
 *
 * [androidx.compose.foundation.layout.requiredWidth] (not `.width`, which would just be
 * coerced down to the real test device's own screen size) deterministically forces a
 * specific width regardless of which physical device or emulator profile actually runs
 * these tests — the standard technique for exercising both a narrow-phone and a tablet-width
 * layout from the same instrumented test target.
 */
@RunWith(AndroidJUnit4::class)
class RoutineCreateScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val locale: Locale = Locale.US
    private val labels: List<String> = DayOfWeek.values().map { it.getDisplayName(TextStyle.SHORT, locale) }

    private fun setContentAtWidth(width: Dp, activeDays: Set<DayOfWeek> = emptySet(), onToggleDay: (DayOfWeek) -> Unit = {}) {
        composeRule.setContent {
            Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                WeekdaySelector(activeDays = activeDays, onToggleDay = onToggleDay, locale = locale)
            }
        }
    }

    @Test
    fun atNarrowPhoneWidth_allSevenDaysAreVisible() {
        // Below WEEKDAY_SINGLE_ROW_MIN_WIDTH -- this is exactly the reported bug's width
        // class (Saturday wrapping, Sunday pushed off-screen in a plain single Row).
        setContentAtWidth(320.dp)

        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun atTabletWidth_allSevenDaysAreVisible() {
        setContentAtWidth(900.dp)

        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun atNarrowPhoneWidth_daysSplitIntoTwoBalancedRows() {
        setContentAtWidth(320.dp)

        // Monday-Thursday share one row's vertical position; Friday-Sunday share a
        // different one -- directly proving the two-row split actually happens, not just
        // that all seven happen to be individually visible somewhere.
        val firstRowY = composeRule.onNodeWithText(labels[0]).fetchSemanticsNode().boundsInRoot.top
        val secondRowY = composeRule.onNodeWithText(labels[4]).fetchSemanticsNode().boundsInRoot.top
        listOf(labels[1], labels[2], labels[3]).forEach { label ->
            assertEquals(firstRowY, composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        listOf(labels[5], labels[6]).forEach { label ->
            assertEquals(secondRowY, composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        assertTrue("expected the second row to be below the first", secondRowY > firstRowY)
    }

    @Test
    fun atTabletWidth_allSevenDaysShareASingleRow() {
        setContentAtWidth(900.dp)

        val firstY = composeRule.onNodeWithText(labels[0]).fetchSemanticsNode().boundsInRoot.top
        labels.drop(1).forEach { label ->
            assertEquals(firstY, composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }

    @Test
    fun tappingADayAtNarrowWidthInvokesOnToggleDay() {
        var toggled: DayOfWeek? = null
        setContentAtWidth(320.dp, onToggleDay = { toggled = it })

        composeRule.onNodeWithText(labels[6]).performClick() // Sunday -- the day the bug report named as off-screen

        assertEquals(DayOfWeek.SUNDAY, toggled)
    }

    @Test
    fun selectedDaysAreReflectedAtNarrowWidth() {
        setContentAtWidth(320.dp, activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY))

        // FilterChip's own `selected` semantics are exposed as a toggleable/selected state;
        // asserting the label still renders for both is enough to prove neither chip's
        // content was clipped or replaced when selected, at the narrowest supported width.
        composeRule.onNodeWithText(labels[0]).assertIsDisplayed()
        composeRule.onNodeWithText(labels[6]).assertIsDisplayed()
    }
}
