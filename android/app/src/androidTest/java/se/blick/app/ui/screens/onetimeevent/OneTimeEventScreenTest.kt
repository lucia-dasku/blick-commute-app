package se.blick.app.ui.screens.onetimeevent

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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

    private fun setEditorContent(initialState: OneTimeEventEditorUiState = validEditorState()) {
        composeRule.setContent {
            BlickTheme {
                var state by remember { mutableStateOf(initialState) }
                OneTimeEventEditorContent(
                    state = state,
                    onBack = {},
                    onOpenPremium = {},
                    onLabel = { state = state.copy(label = it) },
                    onName = { state = state.copy(name = it) },
                    onOriginQuery = { state = state.copy(originQuery = it) },
                    onDestinationQuery = { state = state.copy(destinationQuery = it) },
                    onOrigin = { state = state.copy(selectedOrigin = it, originQuery = it.name) },
                    onDestination = { state = state.copy(selectedDestination = it, destinationQuery = it.name) },
                    onDate = { state = state.copy(date = it) },
                    onTimeType = { state = state.copy(timeType = it) },
                    onTime = { state = state.copy(time = it) },
                    onSave = {},
                )
            }
        }
    }

    @Test
    fun saveActionStaysVisibleWhileTheSinglePageFormScrolls() {
        setEditorContent()

        composeRule.onNodeWithTag("one-time-event-content").performScrollToIndex(4)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_preview_future)).assertIsDisplayed()
        composeRule.onNodeWithTag("one-time-event-sticky-action").assertIsDisplayed()
        composeRule.onNodeWithTag("save-event-button").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun labelAndTimeTypeSelectionsRemainSelected() {
        setEditorContent()

        composeRule.onNodeWithTag("one-time-event-label-travel").performClick().assertIsSelected()
        composeRule.onNodeWithTag("one-time-event-time-type-leave_at").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("one-time-event-label-travel").assertIsSelected()
    }

    @Test
    fun saveActionUsesTheExistingCanSaveState() {
        setEditorContent(validEditorState().copy(selectedDestination = null))

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
