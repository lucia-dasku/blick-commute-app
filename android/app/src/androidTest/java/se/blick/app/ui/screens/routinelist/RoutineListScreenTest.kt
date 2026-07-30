package se.blick.app.ui.screens.routinelist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.TransportMode

/**
 * Instrumented Compose UI test for the restored Add-routine control AND the deliberate
 * first-beta one-routine constraint (see [RoutineListContent]'s own doc). Exercises
 * [RoutineListContent] directly — the stateless composable extracted from [RoutineListScreen] —
 * rather than [RoutineListScreen] itself, so no [RoutineListViewModel]/Hilt test infrastructure
 * is needed: a plain [RoutineListUiState] is enough to drive the empty, populated, and
 * one-routine-limit-dialog cases.
 *
 * None of these tests exercise or claim that a second routine can actually be saved — that
 * remains blocked by `RoutineCreateViewModel.oneRoutineLimitReached` and is out of scope here;
 * these only prove the list screen itself never opens that (doomed-to-fail) creation flow once
 * a routine already exists, showing an explanation in place instead.
 */
@RunWith(AndroidJUnit4::class)
class RoutineListScreenTest {

    // createAndroidComposeRule (not the plain createComposeRule) specifically so this rule
    // exposes `.activity` -- used below to resolve real string resources via
    // `composeRule.activity.getString(...)` rather than hardcoding copies of strings.xml text
    // that could silently drift out of sync with the real resource.
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleRoutine(id: String = "r1", name: String = "Morning commute") = CommuteRoutine(
        id = id,
        name = name,
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    @Test
    fun emptyList_addRoutineFabIsVisibleAndOpensTheCreationFlow() {
        var addRoutineClicked = false

        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = emptyList(), isLoading = false),
                onAddRoutine = { addRoutineClicked = true },
                onOpenRoutine = {},
            )
        }

        val fabDescription = composeRule.activity.getString(R.string.routine_list_add)
        composeRule.onNodeWithContentDescription(fabDescription).apply {
            assertExists()
            performClick()
        }

        assertEquals(true, addRoutineClicked)
    }

    @Test
    fun populatedList_addRoutineFabRemainsVisible() {
        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = listOf(sampleRoutine()), isLoading = false),
                onAddRoutine = {},
                onOpenRoutine = {},
            )
        }

        // The routine itself must still render -- the FAB should not have displaced the list.
        composeRule.onNodeWithText("Morning commute").assertExists()

        val fabDescription = composeRule.activity.getString(R.string.routine_list_add)
        composeRule.onNodeWithContentDescription(fabDescription).assertExists()
    }

    @Test
    fun populatedList_tappingAddShowsTheOneRoutineExplanation_andDoesNotOpenCreation() {
        var addRoutineClicked = false

        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = listOf(sampleRoutine()), isLoading = false),
                onAddRoutine = { addRoutineClicked = true },
                onOpenRoutine = {},
            )
        }

        val fabDescription = composeRule.activity.getString(R.string.routine_list_add)
        composeRule.onNodeWithContentDescription(fabDescription).performClick()

        // The explanation dialog appears...
        val dialogTitle = composeRule.activity.getString(R.string.routine_list_one_routine_limit_title)
        composeRule.onNodeWithText(dialogTitle).assertExists()

        // ...and the creation flow is never opened. This does NOT claim a second routine can
        // be created -- quite the opposite, it proves the list screen never even attempts to
        // navigate to that (would-be-blocked) flow.
        assertEquals(false, addRoutineClicked)
    }

    @Test
    fun populatedList_dismissingTheOneRoutineExplanationClosesIt() {
        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = listOf(sampleRoutine()), isLoading = false),
                onAddRoutine = {},
                onOpenRoutine = {},
            )
        }

        val fabDescription = composeRule.activity.getString(R.string.routine_list_add)
        composeRule.onNodeWithContentDescription(fabDescription).performClick()

        val confirmText = composeRule.activity.getString(R.string.routine_list_one_routine_limit_confirm)
        composeRule.onNodeWithText(confirmText).performClick()

        val dialogTitle = composeRule.activity.getString(R.string.routine_list_one_routine_limit_title)
        composeRule.onNodeWithText(dialogTitle).assertDoesNotExist()
    }

    @Test
    fun openingASavedRoutineInvokesOnOpenRoutine() {
        var openedRoutineId: String? = null

        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = listOf(sampleRoutine(id = "r42")), isLoading = false),
                onAddRoutine = {},
                onOpenRoutine = { openedRoutineId = it },
            )
        }

        composeRule.onNodeWithText("Morning commute").performClick()

        assertEquals("r42", openedRoutineId)
    }

    @Test
    fun editAndDeleteBehaviorAreUnaffected_openingARoutineStillWorksWithTheFabPresent() {
        // Preserves existing edit/delete-entry behaviour (opening the routine, which is how
        // RoutineDetailsScreen's own edit/delete actions are reached) alongside the always
        // -visible FAB introduced by the restored Add-routine control.
        var openedRoutineId: String? = null

        composeRule.setContent {
            RoutineListContent(
                uiState = RoutineListUiState(routines = listOf(sampleRoutine(id = "r7")), isLoading = false),
                onAddRoutine = {},
                onOpenRoutine = { openedRoutineId = it },
            )
        }

        val fabDescription = composeRule.activity.getString(R.string.routine_list_add)
        composeRule.onNodeWithContentDescription(fabDescription).assertExists()
        composeRule.onNodeWithText("Morning commute").performClick()

        assertEquals("r7", openedRoutineId)
    }
}
