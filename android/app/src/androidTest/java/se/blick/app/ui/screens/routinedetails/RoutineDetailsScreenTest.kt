package se.blick.app.ui.screens.routinedetails

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.notification.NotificationAvailability

/**
 * Instrumented Compose test for the "Disruptions" section of [RoutineDetailsContent] — exercises
 * it directly (an `internal` composable, bumped from `private` for exactly this reason, same
 * convention as [se.blick.app.ui.screens.routinecreate.WeekdaySelector]/`RoutineListContent`)
 * rather than the full [RoutineDetailsScreen], which needs a real ViewModel/Hilt component.
 *
 * Covers the section-visibility, collapsed/expanded card content, and muted-red styling this
 * milestone's notification/filtering fix also touches on the Routine Details side.
 */
@RunWith(AndroidJUnit4::class)
class RoutineDetailsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleRoutine() = CommuteRoutine(
        id = "r1",
        name = "Morning commute",
        siteId = 9145,
        siteName = "Fruängen",
        transportMode = TransportMode.METRO,
        lineId = 14,
        lineDesignation = "14",
        directionCode = 1,
        destinationLabel = "T-Centralen",
        activeDays = setOf(DayOfWeek.MONDAY),
        startTime = LocalTime.of(7, 0),
        endTime = LocalTime.of(9, 0),
    )

    private fun disruption(
        id: String = "d1",
        header: String = "Delays on line 14",
        details: String = "Expect longer travel times between Fruängen and T-Centralen.",
    ) = Disruption(
        disruptionId = id,
        version = 1,
        createdAt = Instant.parse("2026-08-02T07:00:00Z"),
        modifiedAt = null,
        validFrom = null,
        validUntil = null,
        priority = DisruptionPriority(1, 1, 1),
        message = DisruptionMessage(header, details, null, null, "en"),
        affectedStopAreas = emptyList(),
        affectedLines = emptyList(),
        affectedModes = emptyList(),
    )

    private fun setContent(disruptionsState: DisruptionsState) {
        composeRule.setContent {
            RoutineDetailsContent(
                modifier = Modifier,
                routine = sampleRoutine(),
                isPausedToday = false,
                departuresState = LiveDeparturesState.Offline,
                isRefreshing = false,
                disruptionsState = disruptionsState,
                onRefresh = {},
                onEdit = {},
                isTogglingEnabled = false,
                enabledActionFailed = false,
                hasSeenNotificationRationale = true,
                onNotificationRationaleSeen = {},
                notificationAvailability = NotificationAvailability.Available,
                onToggleEnabled = {},
                isTogglingPause = false,
                pauseActionFailed = false,
                onPauseToday = {},
                onResumeToday = {},
                isDeleting = false,
                deleteFailed = false,
                onRequestDelete = {},
                onShowDebugNotification = { null },
                onRemoveDebugNotification = {},
                isLiveUpdatePromotable = { false },
            )
        }
    }

    private fun heading(): String = composeRule.activity.getString(R.string.routine_details_disruptions_heading)

    @Test
    fun noRelevantDisruptions_theWholeSectionIsHidden() {
        setContent(DisruptionsState.NoDisruptions)
        composeRule.onNodeWithText(heading()).assertDoesNotExist()
    }

    @Test
    fun loading_headingAndSectionAreShown() {
        setContent(DisruptionsState.Loading)
        composeRule.onNodeWithText(heading()).assertExists()
    }

    @Test
    fun unavailable_headingAndMessageAreShown() {
        setContent(DisruptionsState.Unavailable)
        composeRule.onNodeWithText(heading()).assertExists()
        val message = composeRule.activity.getString(R.string.routine_details_disruptions_unavailable)
        composeRule.onNodeWithText(message).assertExists()
    }

    @Test
    fun loaded_collapsedByDefault_showsOnlyTheHeader() {
        val d = disruption()
        setContent(DisruptionsState.Loaded(listOf(d)))

        composeRule.onNodeWithText(heading()).assertExists()
        composeRule.onNodeWithText(d.message.header).assertExists()
        composeRule.onNodeWithText(d.message.details).assertDoesNotExist()
    }

    @Test
    fun loaded_expandingRevealsTheDetails() {
        val d = disruption()
        setContent(DisruptionsState.Loaded(listOf(d)))

        // The card sits below several other sections in RoutineDetailsContent's scrollable
        // Column -- performScrollTo() is required first since the button can be off-screen at
        // the initial scroll position, and a click at an off-screen coordinate is silently a
        // no-op on a real device rather than throwing.
        val expandDescription = composeRule.activity.getString(R.string.routine_details_disruption_expand)
        composeRule.onNodeWithContentDescription(expandDescription).performScrollTo().performClick()

        composeRule.onNodeWithText(d.message.details).assertExists()
    }

    @Test
    fun loaded_collapsingAfterExpandingHidesTheDetailsAgain() {
        val d = disruption()
        setContent(DisruptionsState.Loaded(listOf(d)))

        val expandDescription = composeRule.activity.getString(R.string.routine_details_disruption_expand)
        composeRule.onNodeWithContentDescription(expandDescription).performScrollTo().performClick()
        val collapseDescription = composeRule.activity.getString(R.string.routine_details_disruption_collapse)
        composeRule.onNodeWithContentDescription(collapseDescription).performScrollTo().performClick()

        composeRule.onNodeWithText(d.message.details).assertDoesNotExist()
    }

    @Test
    fun loaded_multipleDisruptionsEachRenderTheirOwnHeader() {
        val first = disruption(id = "d1", header = "Delays on line 14")
        val second = disruption(id = "d2", header = "Escalator out of service")
        setContent(DisruptionsState.Loaded(listOf(first, second)))

        composeRule.onNodeWithText(first.message.header).assertExists()
        composeRule.onNodeWithText(second.message.header).assertExists()
    }
}
