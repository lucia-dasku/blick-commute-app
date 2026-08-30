package se.blick.app.ui.screens.onetimeevent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.TransportMode
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class OneTimeEventPlannedJourneyScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun preliminaryPrimaryShowsAbsoluteTimesLineDisclaimerAndNoLiveText() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(
                    preview = readyPreview(),
                    locale = Locale.ENGLISH,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_preliminary_plan_title)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_recommended)).assertIsDisplayed()
        composeRule.onNodeWithText("17:36 → 18:18").assertIsDisplayed()
        composeRule.onNodeWithText("19").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_preliminary_plan_disclaimer)).assertIsDisplayed()
        composeRule.onNodeWithTag("one-time-event-plan-info").assertIsDisplayed()
        composeRule.onNodeWithText("Live").assertDoesNotExist()
    }

    @Test
    fun backendPrimaryAndNextRenderTheirOwnHeadingAndJourneyData() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(
                    preview = readyPreview(includeNext = true),
                    locale = Locale.ENGLISH,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_recommended)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_next_option)).assertExists()
        composeRule.onNodeWithText("17:36 → 18:18").assertIsDisplayed()
        composeRule.onNodeWithText("17:46 → 18:28").assertIsDisplayed()
    }

    @Test
    fun todayPlanShowsFetchedTimeButNeverLiveLabel() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(
                    preview = readyPreview(),
                    locale = Locale.ENGLISH,
                    presentation = EventPlanPresentation.TODAY,
                    disruptionState = EventPlanDisruptionState.Ready(emptyList()),
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_today_plan_title)).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.one_time_event_plan_updated, "12:00"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_today_plan_explanation)).assertIsDisplayed()
        composeRule.onNodeWithTag("one-time-event-plan-info").assertIsDisplayed()
        composeRule.onNodeWithText("Live").assertDoesNotExist()
    }

    @Test
    fun missingNextAndAlternativeCreateNoEmptyHeadings() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(preview = readyPreview(), locale = Locale.ENGLISH, onRefresh = {})
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_next_option)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_alternative)).assertDoesNotExist()
    }

    @Test
    fun errorShowsRetryAndInvokesIt() {
        var retried = false
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(
                    preview = PlannedJourneyPreviewState.Error,
                    locale = Locale.ENGLISH,
                    onRefresh = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_planned_error)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_planned_retry)).performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    private fun readyPreview(
        includeNext: Boolean = false,
        includeAlternative: Boolean = false,
    ): PlannedJourneyPreviewState.Ready {
        val firstLeg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "19",
            direction = "Hagsätra",
            originName = "Home",
            destinationName = "T-Centralen",
            departureTime = Instant.parse("2026-09-17T15:36:00Z"),
            arrivalTime = Instant.parse("2026-09-17T15:51:00Z"),
            isRealtime = false,
            disruptions = emptyList(),
        )
        val secondLeg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "19",
            direction = "Hagsätra",
            originName = "T-Centralen",
            destinationName = "Globen",
            departureTime = Instant.parse("2026-09-17T16:00:00Z"),
            arrivalTime = Instant.parse("2026-09-17T16:18:00Z"),
            isRealtime = false,
            disruptions = emptyList(),
        )
        val journey = JourneyPlan(
            journeyId = "primary",
            originName = "Home",
            destinationName = "Globen",
            departureTime = requireNotNull(firstLeg.departureTime),
            arrivalTime = requireNotNull(secondLeg.arrivalTime),
            transferCount = 1,
            firstLeg = firstLeg,
            legs = listOf(firstLeg, secondLeg),
            disruptions = emptyList(),
            role = JourneyRole.PRIMARY,
        )
        val nextFirstLeg = firstLeg.copy(
            departureTime = Instant.parse("2026-09-17T15:46:00Z"),
            arrivalTime = Instant.parse("2026-09-17T16:01:00Z"),
        )
        val nextSecondLeg = secondLeg.copy(
            departureTime = Instant.parse("2026-09-17T16:10:00Z"),
            arrivalTime = Instant.parse("2026-09-17T16:28:00Z"),
        )
        val nextJourney = journey.copy(
            journeyId = "next",
            departureTime = requireNotNull(nextFirstLeg.departureTime),
            arrivalTime = requireNotNull(nextSecondLeg.arrivalTime),
            firstLeg = nextFirstLeg,
            legs = listOf(nextFirstLeg, nextSecondLeg),
            role = JourneyRole.NEXT,
        )
        val journeys = buildList {
            add(journey)
            if (includeNext) add(nextJourney)
            if (includeAlternative) add(journey.copy(journeyId = "alternative", role = JourneyRole.ALTERNATIVE))
        }
        return PlannedJourneyPreviewState.Ready(
            primary = journey,
            result = PlannedJourneyResult(
                fetchedAt = Instant.parse("2026-09-01T10:00:00Z"),
                searchMode = JourneySearchMode.ARRIVE_BY,
                requestedDateTime = Instant.parse("2026-09-17T16:30:00Z"),
                journeys = journeys,
            ),
        )
    }
}
