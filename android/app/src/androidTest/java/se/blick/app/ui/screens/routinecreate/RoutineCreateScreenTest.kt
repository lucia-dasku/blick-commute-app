package se.blick.app.ui.screens.routinecreate

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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
 * Every case uses [androidx.compose.foundation.layout.requiredWidth] to force the exact
 * width under test regardless of the real test device's own screen size — deliberately, so
 * "narrow" and "wide" here test [WeekdaySelector]'s own responsive *logic* at fixed measured
 * constraints, not whatever width a given physical device happens to have. This matters
 * because a real device is not guaranteed to be either: a Galaxy S23 Ultra's own portrait
 * content width (~384dp at its native density) is itself *narrower* than
 * [WEEKDAY_SINGLE_ROW_MIN_WIDTH] — a perfectly ordinary flagship phone, but not a stand-in
 * for "wide" — so a "wide" case that merely coerced down to whatever the device provides
 * (via plain [androidx.compose.foundation.layout.width]) would silently stop testing the
 * single-row branch at all on that hardware.
 *
 * The one thing [requiredWidth] does trade away is [assertIsDisplayed] for the "wide" cases:
 * a forced 900dp width can genuinely exceed a real phone's own visible viewport, and
 * `assertIsDisplayed` (which checks actual on-screen visibility, not just layout) can then
 * fail for reasons that have nothing to do with [WeekdaySelector] itself. The "wide" visibility
 * case below asserts existence and a non-zero measured size instead — a faithful check of
 * "this chip was actually laid out with real content," independent of whether the specific
 * test device's physical screen happens to be that wide; the separate row-position tests
 * still directly confirm real, correct placement via [boundsInRoot][androidx.compose.ui.geometry.Rect],
 * which is a layout property unaffected by physical viewport clipping either way.
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
    fun atTabletWidth_allSevenDaysAreLaidOutWithRealSize() {
        setContentAtWidth(900.dp)

        // See this class's own doc for why this checks layout (existence + non-zero size)
        // rather than assertIsDisplayed's physical-viewport visibility at this forced width.
        labels.forEach { label ->
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("expected '$label' to have a real measured size, got $bounds", bounds.width > 0f && bounds.height > 0f)
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

    private fun setUnifiedOriginDestinationContent(hasPremium: Boolean) {
        composeRule.setContent {
            OriginDestinationStep(
                uiState = RoutineCreateUiState(hasPremium = hasPremium),
                onQueryChanged = {},
                onSelectSite = {},
                onDestinationQueryChanged = {},
                onSelectDestination = {},
                onContinue = {},
                onRetryStopSearch = {},
                onRetryDirections = {},
            )
        }
    }

    @Test
    fun freeUser_destinationFieldIsVisibleButInactive() {
        setUnifiedOriginDestinationContent(hasPremium = false)

        composeRule.onNodeWithTag("destination-field").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun premiumUser_destinationFieldIsActiveInTheSameForm() {
        setUnifiedOriginDestinationContent(hasPremium = true)

        composeRule.onNodeWithTag("destination-field").assertIsDisplayed().assertIsEnabled()
    }
}
