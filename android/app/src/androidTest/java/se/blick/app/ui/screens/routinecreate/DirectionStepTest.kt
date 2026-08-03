package se.blick.app.ui.screens.routinecreate

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.data.repository.DirectionOption
import se.blick.app.domain.model.TransportMode

/**
 * Instrumented Compose UI test for [DirectionStep] — exercises it directly (`internal`, bumped
 * from `private` for exactly this reason, same convention as [WeekdaySelector]/
 * `RoutineDetailsContent`) rather than the full [RoutineCreateScreen], which needs a real
 * ViewModel/Hilt component and a completed stop/mode selection to reach this step at all.
 *
 * Covers the shared line-number badge (see [se.blick.app.ui.components.LineBadge]) this route-
 * selection step now shows for each option, alongside the pre-existing destination text and
 * selection callback.
 */
@RunWith(AndroidJUnit4::class)
class DirectionStepTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun option(
        lineId: Long = 14,
        lineDesignation: String = "14",
        transportMode: TransportMode = TransportMode.METRO,
        directionCode: Int? = 1,
        destinationLabel: String? = "T-Centralen",
    ) = DirectionOption(lineId, lineDesignation, transportMode, directionCode, destinationLabel)

    private fun setContent(options: List<DirectionOption>, onSelectDirection: (DirectionOption) -> Unit = {}) {
        composeRule.setContent {
            DirectionStep(
                uiState = RoutineCreateUiState(directionOptions = options, selectedTransportMode = options.firstOrNull()?.transportMode),
                onSelectDirection = onSelectDirection,
            )
        }
    }

    @Test
    fun eachOptionShowsTheSharedLineBadgeForItsOwnLine() {
        setContent(listOf(option(lineId = 14, lineDesignation = "14"), option(lineId = 18, lineDesignation = "18")))

        composeRule.onNodeWithText("14").assertIsDisplayed()
        composeRule.onNodeWithText("18").assertIsDisplayed()
    }

    @Test
    fun eachOptionStillShowsItsDestination() {
        setContent(listOf(option(destinationLabel = "T-Centralen")))
        composeRule.onNodeWithText("T-Centralen").assertIsDisplayed()
    }

    @Test
    fun tappingAnOptionInvokesOnSelectDirectionWithThatOption() {
        var selected: DirectionOption? = null
        val target = option(lineId = 18, lineDesignation = "18", destinationLabel = "Farsta strand")
        setContent(listOf(option(lineId = 14, lineDesignation = "14"), target), onSelectDirection = { selected = it })

        composeRule.onNodeWithText("Farsta strand").performClick()

        assertEquals(target, selected)
    }

    @Test
    fun optionsForOtherTransportModesAreFilteredOut() {
        // DirectionStep only shows options matching uiState.selectedTransportMode -- a bus
        // option present in directionOptions but for a different mode must not render.
        composeRule.setContent {
            DirectionStep(
                uiState = RoutineCreateUiState(
                    directionOptions = listOf(
                        option(lineId = 14, lineDesignation = "14", transportMode = TransportMode.METRO),
                        option(lineId = 705, lineDesignation = "705", transportMode = TransportMode.BUS, destinationLabel = "Skärholmen"),
                    ),
                    selectedTransportMode = TransportMode.METRO,
                ),
                onSelectDirection = {},
            )
        }

        composeRule.onNodeWithText("14").assertIsDisplayed()
        composeRule.onNodeWithText("705").assertDoesNotExist()
    }
}
