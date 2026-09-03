package se.blick.app.ui.screens.onetimeevent

import android.graphics.Rect
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class OneTimeEventScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun validEditorState() = OneTimeEventEditorUiState(
        isLoading = false,
        hasPremium = true,
        label = OneTimeEventLabel.EVENT,
        name = "Dinner",
        originQuery = "Home",
        selectedOrigin = JourneyLocation("home", "Home"),
        destinationQuery = "Södermalm",
        selectedDestination = JourneyLocation("destination", "Södermalm"),
        date = LocalDate.of(2026, 9, 17),
        time = LocalTime.of(18, 30),
        timeType = OneTimeEventTimeType.ARRIVE_BY,
    )

    private fun setEditorContent(
        initialState: OneTimeEventEditorUiState = validEditorState(),
        searchResults: List<JourneyLocation> = emptyList(),
        useDarkTheme: Boolean = false,
        useStockholmNightTheme: Boolean = false,
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            BlickTheme(useDarkTheme = useDarkTheme, useStockholmNightTheme = useStockholmNightTheme) {
                var state by remember { mutableStateOf(initialState) }
                OneTimeEventEditorContent(
                    state = state,
                    onBack = {},
                    onOpenPremium = {},
                    onLabel = { state = state.copy(label = it) },
                    onName = { state = state.copy(name = it) },
                    onOriginQuery = {
                        state = state.copy(originQuery = it, selectedOrigin = null, originResults = searchResults)
                    },
                    onDestinationQuery = {
                        state = state.copy(destinationQuery = it, selectedDestination = null, destinationResults = searchResults)
                    },
                    onOrigin = { state = state.copy(selectedOrigin = it, originQuery = it.name, originResults = emptyList()) },
                    onDestination = {
                        state = state.copy(selectedDestination = it, destinationQuery = it.name, destinationResults = emptyList())
                    },
                    onDate = { state = state.copy(date = it) },
                    onTimeType = { state = state.copy(timeType = it) },
                    onTime = { state = state.copy(time = it) },
                    onSave = onSave,
                )
            }
        }
    }

    @Test
    fun fromSuggestionsCloseOnSelectionAndInFormSaveRemainsEnabled() {
        setEditorContent(searchResults = stopResults())
        assertSearchSelectionAllowsInFormSave("origin")
    }

    @Test
    fun toSuggestionsCloseOnSelectionAndInFormSaveRemainsEnabledWhileEditing() {
        setEditorContent(
            initialState = validEditorState().copy(isEditing = true),
            searchResults = stopResults(),
            useDarkTheme = true,
        )
        assertSearchSelectionAllowsInFormSave("destination")
    }

    @Test
    fun dismissingSearchLeavesInFormSaveDisabledForAnUnselectedStop() {
        setEditorContent(searchResults = stopResults())
        openStopSearch("origin")

        composeRule.onNodeWithTag("one-time-event-sticky-action").assertDoesNotExist()
        composeRule.onNodeWithTag("one-time-event-origin-field").performImeAction()

        composeRule.onNodeWithTag("one-time-event-origin-suggestions").assertDoesNotExist()
        scrollToSave()
        composeRule.onNodeWithTag("save-event-button").assertIsNotEnabled()
    }

    @Test
    fun longFromResultsScrollAboveImeInSmallWindow() {
        assertLongResultsScrollWithKeyboard("origin", useStockholmNightTheme = false)
    }

    @Test
    fun longToResultsScrollAboveImeInSmallStockholmNightWindow() {
        assertLongResultsScrollWithKeyboard("destination", useStockholmNightTheme = true)
    }

    private fun stopResults() = (1..30).map { JourneyLocation("stop-$it", "Stop $it") }

    private fun openStopSearch(field: String) {
        composeRule.onNodeWithTag("one-time-event-$field-field")
            .performScrollTo()
            .performClick()
            .performTextReplacement("Stop")
        composeRule.onNodeWithTag("one-time-event-$field-suggestions").assertIsDisplayed()
    }

    private fun assertSearchSelectionAllowsInFormSave(field: String) {
        openStopSearch(field)
        composeRule.onNodeWithTag("one-time-event-sticky-action").assertDoesNotExist()

        composeRule.onNodeWithTag("one-time-event-$field-result-stop-1")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("one-time-event-$field-suggestions").assertDoesNotExist()
        scrollToSave()
        composeRule.onNodeWithTag("save-event-button").assertIsEnabled()
    }

    private fun assertLongResultsScrollWithKeyboard(field: String, useStockholmNightTheme: Boolean) {
        setEditorContent(searchResults = stopResults(), useStockholmNightTheme = useStockholmNightTheme)
        useSmallWindow()
        openStopSearch(field)
        composeRule.waitUntil(timeoutMillis = 10_000) { keyboardIsVisible() }
        composeRule.onNodeWithTag("one-time-event-sticky-action").assertDoesNotExist()

        val lastResult = composeRule.onNodeWithTag("one-time-event-$field-result-stop-30")
        lastResult.performScrollTo().assertIsDisplayed()
        val menu = composeRule.onNodeWithTag("one-time-event-$field-suggestions").fetchSemanticsNode()
        val visibleFrame = Rect()
        composeRule.runOnIdle {
            composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            assertTrue("The keyboard must remain open while scrolling results", keyboardIsVisible())
        }
        assertTrue("Menu must stay below the system top inset", menu.positionOnScreen.y >= visibleFrame.top - 1)
        assertTrue(
            "Menu must remain above the keyboard",
            menu.positionOnScreen.y + menu.size.height <= visibleFrame.bottom + 1,
        )

        lastResult.performClick()
        composeRule.onNodeWithTag("one-time-event-$field-suggestions").assertDoesNotExist()
        scrollToSave()
        composeRule.onNodeWithTag("save-event-button").assertIsDisplayed().assertIsEnabled()
    }

    private fun keyboardIsVisible(): Boolean =
        ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun saveActionIsTheLastFormItemAndScrollsAwayWithTheForm() {
        setEditorContent()
        useSmallWindow()

        scrollToSave()
        composeRule.onNodeWithTag("one-time-event-sticky-action").assertDoesNotExist()
        val saveButton = composeRule.onNodeWithTag("save-event-button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assert(hasAnyAncestor(hasTestTag("one-time-event-content")))
            .fetchSemanticsNode()
        val preview = composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_preview_future))
            .fetchSemanticsNode()
        assertTrue(saveButton.positionInRoot.y >= preview.positionInRoot.y + preview.size.height)

        composeRule.onNodeWithTag("one-time-event-content").performScrollToIndex(0)
        composeRule.onNodeWithTag("save-event-button").assertIsNotDisplayed()
    }

    @Test
    fun createFormSaveCanBeReachedAndClickedInSmallWindowWithKeyboardOpen() {
        assertSaveReachableWithKeyboard(isEditing = false)
    }

    @Test
    fun editFormSaveCanBeReachedAndClickedInSmallWindowWithKeyboardOpen() {
        assertSaveReachableWithKeyboard(isEditing = true)
    }

    private fun assertSaveReachableWithKeyboard(isEditing: Boolean) {
        var saved = false
        setEditorContent(validEditorState().copy(isEditing = isEditing), onSave = { saved = true })
        useSmallWindow()
        composeRule.onNodeWithTag("one-time-event-content")
            .performScrollToNode(hasTestTag("one-time-event-name-field"))
        composeRule.onNodeWithTag("one-time-event-name-field").performClick().performTextReplacement("Dinner")
        composeRule.waitUntil(timeoutMillis = 10_000) { keyboardIsVisible() }

        scrollToSave()
        val button = composeRule.onNodeWithTag("save-event-button").assertIsDisplayed().assertIsEnabled()
        val bounds = button.fetchSemanticsNode()
        val visibleFrame = Rect()
        composeRule.runOnIdle {
            composeRule.activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            assertTrue("The form must scroll without dismissing the keyboard", keyboardIsVisible())
        }
        assertTrue("Save must remain above the keyboard", bounds.positionOnScreen.y + bounds.size.height <= visibleFrame.bottom)
        button.performClick()
        composeRule.runOnIdle { assertTrue(saved) }
    }

    private fun scrollToSave() {
        composeRule.onNodeWithTag("one-time-event-content").performScrollToNode(hasTestTag("save-event-button"))
    }

    @Suppress("DEPRECATION")
    private fun useSmallWindow() {
        composeRule.runOnIdle {
            val metrics = composeRule.activity.resources.displayMetrics
            composeRule.activity.window.setLayout(
                (360 * metrics.density).toInt().coerceAtMost(metrics.widthPixels),
                (640 * metrics.density).toInt().coerceAtMost(metrics.heightPixels),
            )
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun labelAndTimeTypeSelectionsRemainSelected() {
        setEditorContent()

        composeRule.onNodeWithTag("one-time-event-label-travel").performClick().assertIsSelected()
        composeRule.onNodeWithTag("one-time-event-label-event").assertIsNotSelected()
        composeRule.onNodeWithTag("one-time-event-time-type-leave_at").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("one-time-event-label-travel").assertIsSelected()
    }

    @Test
    fun allEventLabelsRenderAndSelectionMovesToExactlyOneLabel() {
        setEditorContent()

        OneTimeEventLabel.entries.forEach { selectedLabel ->
            val selectedTag = "one-time-event-label-${selectedLabel.name.lowercase()}"
            composeRule.onNodeWithTag(selectedTag).assertIsDisplayed().performClick().assertIsSelected()
            OneTimeEventLabel.entries.filterNot { it == selectedLabel }.forEach { unselectedLabel ->
                composeRule.onNodeWithTag("one-time-event-label-${unselectedLabel.name.lowercase()}")
                    .assertIsNotSelected()
            }
        }
    }

    @Test
    fun editingExistingEventShowsItsSavedLabelSelectedImmediately() {
        setEditorContent(
            validEditorState().copy(
                isEditing = true,
                label = OneTimeEventLabel.APPOINTMENT,
            ),
        )

        composeRule.onNodeWithTag("one-time-event-label-appointment").assertIsSelected()
        composeRule.onNodeWithTag("one-time-event-label-travel").assertIsNotSelected()
        composeRule.onNodeWithTag("one-time-event-label-event").assertIsNotSelected()
        composeRule.onNodeWithTag("one-time-event-label-other").assertIsNotSelected()
    }

    @Test
    fun eventLabelSelectorRendersAcrossEffectiveLightDarkAndStockholmThemes() {
        var useDarkTheme by mutableStateOf<Boolean?>(false)
        var systemDarkTheme by mutableStateOf(false)
        var useStockholmNightTheme by mutableStateOf(false)
        composeRule.setContent {
            BlickTheme(
                useDarkTheme = useDarkTheme,
                useStockholmNightTheme = useStockholmNightTheme,
                systemDarkTheme = systemDarkTheme,
            ) {
                OneTimeEventLabelSelector(
                    selectedLabel = OneTimeEventLabel.EVENT,
                    onLabelSelected = {},
                )
            }
        }

        composeRule.onNodeWithTag("one-time-event-label-event").assertIsSelected()
        composeRule.runOnIdle { useDarkTheme = true }
        composeRule.onNodeWithTag("one-time-event-label-event").assertIsSelected()
        composeRule.runOnIdle {
            useDarkTheme = null
            systemDarkTheme = true
        }
        composeRule.onNodeWithTag("one-time-event-label-event").assertIsSelected()
        composeRule.runOnIdle { useStockholmNightTheme = true }
        composeRule.onNodeWithTag("one-time-event-label-event").assertIsSelected()
        OneTimeEventLabel.entries.filterNot { it == OneTimeEventLabel.EVENT }.forEach { label ->
            composeRule.onNodeWithTag("one-time-event-label-${label.name.lowercase()}").assertIsNotSelected()
        }
    }

    @Test
    fun saveActionUsesTheExistingCanSaveState() {
        setEditorContent(validEditorState().copy(selectedDestination = null))

        scrollToSave()
        composeRule.onNodeWithTag("save-event-button").assertIsNotEnabled()
    }

    @Test
    fun freeUserRetainsThePremiumAction() {
        var openedPremium = false
        composeRule.setContent {
            BlickTheme {
                OneTimeEventEditorContent(
                    state = validEditorState().copy(hasPremium = false),
                    onBack = {},
                    onOpenPremium = { openedPremium = true },
                    onLabel = {},
                    onName = {},
                    onOriginQuery = {},
                    onDestinationQuery = {},
                    onOrigin = {},
                    onDestination = {},
                    onDate = {},
                    onTimeType = {},
                    onTime = {},
                    onSave = {},
                )
            }
        }

        scrollToSave()
        composeRule.onNodeWithTag("save-event-button").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(openedPremium) }
    }

    @Test
    fun detailsRenderOneSummaryAndKeepEditAndDeleteAccessible() {
        val event = OneTimeEvent(
            id = "event-1",
            label = OneTimeEventLabel.APPOINTMENT,
            name = "Dentist",
            originId = "home",
            originName = "Home",
            destinationId = "dentist",
            destinationName = "Dental clinic",
            date = LocalDate.of(2026, 9, 17),
            time = LocalTime.of(14, 45),
            timeType = OneTimeEventTimeType.ARRIVE_BY,
            createdAt = Instant.parse("2026-08-29T10:00:00Z"),
        )
        var editedId: String? = null
        composeRule.setContent {
            BlickTheme {
                OneTimeEventDetailsContent(
                    state = OneTimeEventDetailsUiState(
                        isLoading = false,
                        event = event,
                        preview = PlannedJourneyPreviewState.Error,
                    ),
                    onBack = {},
                    onEdit = { editedId = it },
                    onDelete = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("one-time-event-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("one-time-event-label-pill-appointment").assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.one_time_event_route_format, "Home", "Dental clinic"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.one_time_event_arrive_by_value, "14:45"),
        ).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_edit_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(event.id, editedId) }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_delete_action))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
