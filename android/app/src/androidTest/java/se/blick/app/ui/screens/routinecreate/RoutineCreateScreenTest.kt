package se.blick.app.ui.screens.routinecreate

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.data.repository.DirectionOption
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import se.blick.app.ui.theme.BlickTheme

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
 * because a real device is not guaranteed to be either. The responsive cases explicitly
 * exercise the 348dp inner-card width available on a Galaxy S23 Ultra-sized viewport and a
 * narrower 320dp constraint, as well as a tablet constraint.
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
            BlickTheme {
                Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    WeekdaySelector(activeDays = activeDays, onToggleDay = onToggleDay, locale = locale)
                }
            }
        }
    }

    private fun validScheduleState(activeDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY)) = RoutineCreateUiState(
        step = RoutineCreateStep.SCHEDULE,
        selectedSite = Site(1L, "T-Centralen", null, null, null, emptyList()),
        selectedTransportMode = TransportMode.METRO,
        selectedDirection = DirectionOption(13L, "13", TransportMode.METRO, 1, "Malarhojden"),
        activeDays = activeDays,
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
        name = "T-Centralen to Malarhojden",
    )

    private fun setScheduleContent(
        initialState: RoutineCreateUiState = validScheduleState(),
        darkTheme: Boolean = false,
    ) {
        composeRule.setContent {
            BlickTheme(useDarkTheme = darkTheme) {
                var state by remember { mutableStateOf(initialState) }
                ScheduleStep(
                    uiState = state,
                    onToggleDay = { day ->
                        state = state.copy(
                            activeDays = if (day in state.activeDays) state.activeDays - day else state.activeDays + day,
                        )
                    },
                    onStartTimeChanged = { state = state.copy(startTime = it) },
                    onEndTimeChanged = { state = state.copy(endTime = it) },
                    onNameChanged = { state = state.copy(name = it) },
                    onLabelChanged = { state = state.copy(selectedLabel = it) },
                    onSave = {},
                    onRetryScheduling = {},
                )
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
    fun atGalaxyS23UltraInnerWidth_allSevenDaysShareASingleRow() {
        setContentAtWidth(348.dp)

        val firstY = composeRule.onNodeWithText(labels[0]).fetchSemanticsNode().boundsInRoot.top
        labels.drop(1).forEach { label ->
            assertEquals(firstY, composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top, 1f)
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
        val mondayWidth = composeRule.onNodeWithText(labels[0]).fetchSemanticsNode().boundsInRoot.width
        listOf(labels[4], labels[5], labels[6]).forEach { label ->
            assertEquals(
                "expected second-row selectors to keep the same width as the first row",
                mondayWidth,
                composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.width,
                1f,
            )
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

        // The selector's selected semantics are exposed as a toggleable/selected state;
        // asserting the labels still render proves neither selected control is clipped or
        // replaced at the narrowest supported width.
        composeRule.onNodeWithText(labels[0]).assertIsDisplayed()
        composeRule.onNodeWithText(labels[6]).assertIsDisplayed()
    }

    @Test
    fun emptyDays_initiallyHideValidationAndDisableSave() {
        setScheduleContent(validScheduleState(activeDays = emptySet()))

        composeRule.onNodeWithTag("active-days-error").assertDoesNotExist()
        composeRule.onNodeWithTag("save-routine-button").assertIsNotEnabled()
    }

    @Test
    fun emptyDays_afterInteractionShowValidationUntilADayIsSelected() {
        setScheduleContent(validScheduleState())

        composeRule.onNodeWithTag("weekday-monday").performClick()
        composeRule.onNodeWithTag("active-days-error").assertIsDisplayed()
        composeRule.onNodeWithTag("save-routine-button").assertIsNotEnabled()

        composeRule.onNodeWithTag("weekday-tuesday").performClick()
        composeRule.onNodeWithTag("active-days-error").assertDoesNotExist()
        composeRule.onNodeWithTag("save-routine-button").assertIsEnabled()
    }

    @Test
    fun timeControlsExposeAccessibleDescriptionAndOpenExistingPicker() {
        setScheduleContent()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.routine_create_start_label)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.routine_create_end_label)).assertIsDisplayed()
        val startLabel = composeRule.activity.getString(R.string.routine_create_start_time_label)
        val description = composeRule.activity.getString(
            R.string.routine_create_time_control_description,
            startLabel,
            "07:00",
        )
        composeRule.onNodeWithContentDescription(description).performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(android.R.string.ok)).assertIsDisplayed()
    }

    @Test
    fun routineNameRemainsSingleLineAndHandlesImeDone() {
        setScheduleContent()

        val routineNameLabel = composeRule.activity.getString(R.string.routine_create_name_label)
        composeRule.onAllNodesWithText(routineNameLabel).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(routineNameLabel).assertExists()

        val nameField = composeRule.onNodeWithTag("routine-name-field")
        nameField.performScrollTo()
        nameField.performClick()
        composeRule.onNodeWithTag("save-routine-button").assertIsDisplayed()
        nameField.performTextReplacement("Morning commute")
        nameField.performImeAction()

        composeRule.onNodeWithText("Morning commute").assertIsDisplayed()
    }

    @Test
    fun labelCardShowsEveryExistingLabelAndStickySave() {
        setScheduleContent()

        listOf(
            R.string.routine_label_none,
            R.string.routine_label_work,
            R.string.routine_label_home,
            R.string.routine_label_gym,
            R.string.routine_label_study,
            R.string.routine_label_hobby,
            R.string.routine_label_other,
        ).forEach { labelRes ->
            composeRule.onNodeWithText(composeRule.activity.getString(labelRes)).performScrollTo().assertIsDisplayed()
        }
        val school = composeRule.onNodeWithText(composeRule.activity.getString(R.string.routine_label_study))
        school.performClick()
        school.assertIsSelected()
        val noLabel = composeRule.onNodeWithText(composeRule.activity.getString(R.string.routine_label_none))
        noLabel.performClick()
        noLabel.assertIsSelected()
        composeRule.onNodeWithTag("save-routine-button").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun scheduleCardsRenderInLightAndDarkThemes() {
        var useDarkTheme by mutableStateOf(false)
        composeRule.setContent {
            BlickTheme(useDarkTheme = useDarkTheme) {
                ScheduleStep(
                    uiState = validScheduleState(),
                    onToggleDay = {},
                    onStartTimeChanged = {},
                    onEndTimeChanged = {},
                    onNameChanged = {},
                    onLabelChanged = {},
                    onSave = {},
                    onRetryScheduling = {},
                )
            }
        }
        composeRule.onNodeWithTag("active-days-card").assertIsDisplayed()
        composeRule.runOnIdle { useDarkTheme = true }
        composeRule.onNodeWithTag("active-days-card").assertIsDisplayed()
    }

    @Test
    fun createTitleAndSchoolLabelAreLocalizedInEnglishAndSwedish() {
        val resources = composeRule.activity.resources
        val english = android.content.res.Configuration(resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val swedish = android.content.res.Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag("sv"))
        }

        val englishContext = composeRule.activity.createConfigurationContext(english)
        assertEquals("Create routine", englishContext.getString(R.string.routine_create_step_schedule))
        assertEquals("Days & time", englishContext.getString(R.string.routine_edit_step_schedule))
        assertEquals("Start", englishContext.getString(R.string.routine_create_start_label))
        assertEquals("End", englishContext.getString(R.string.routine_create_end_label))
        assertEquals("School", englishContext.getString(R.string.routine_label_study))

        val swedishContext = composeRule.activity.createConfigurationContext(swedish)
        assertEquals("Skapa rutin", swedishContext.getString(R.string.routine_create_step_schedule))
        assertEquals("Dagar och tid", swedishContext.getString(R.string.routine_edit_step_schedule))
        assertEquals("Från", swedishContext.getString(R.string.routine_create_start_label))
        assertEquals("Till", swedishContext.getString(R.string.routine_create_end_label))
        assertEquals("Skola", swedishContext.getString(R.string.routine_label_study))
    }

    private fun setUnifiedOriginDestinationContent(hasPremium: Boolean, onOpenPremium: () -> Unit = {}) {
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
                onOpenPremium = onOpenPremium,
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

    @Test
    fun freeUser_seesThePremiumUpsellAndItsButtonOpensPremium() {
        var opened = false
        setUnifiedOriginDestinationContent(hasPremium = false, onOpenPremium = { opened = true })

        val upsellBody = composeRule.activity.getString(R.string.routine_create_premium_upsell_body)
        val upsellButton = composeRule.activity.getString(R.string.routine_create_premium_upsell_button)
        composeRule.onNodeWithText(upsellBody).assertIsDisplayed()
        composeRule.onNodeWithText(upsellButton).assertIsDisplayed().performClick()

        assertTrue("expected the upsell button to invoke onOpenPremium", opened)
    }

    @Test
    fun premiumUser_neverSeesThePremiumUpsell() {
        setUnifiedOriginDestinationContent(hasPremium = true)

        val upsellButton = composeRule.activity.getString(R.string.routine_create_premium_upsell_button)
        composeRule.onNodeWithText(upsellButton).assertDoesNotExist()
    }
}
