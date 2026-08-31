package se.blick.app.ui.screens.onetimeevent

import androidx.activity.ComponentActivity
import android.content.res.Configuration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneySearchMode
import se.blick.app.domain.model.PlannedJourneyChoice
import se.blick.app.domain.model.PlannedJourneyResult
import se.blick.app.domain.model.PlannedJourneyRole
import se.blick.app.domain.model.TransportMode
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class OneTimeEventPlannedJourneyScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun arriveByShowsPlannedLabelsAbsoluteTimesDisclaimerAndNoLiveRoleText() {
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
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_earlier_option)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_later_option)).assertExists()
        composeRule.onNodeWithText("17:46 → 18:28").assertIsDisplayed()
        composeRule.onAllNodesWithText("19").assertCountEquals(3)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_preliminary_plan_disclaimer)).assertIsDisplayed()
        composeRule.onNodeWithTag("one-time-event-plan-info").assertIsDisplayed()
        composeRule.onNodeWithText("Live").assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_next)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_alternative)).assertDoesNotExist()
    }

    @Test
    fun leaveAtUsesTheSameEarlierRecommendedLaterTerminology() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(
                    preview = readyPreview(searchMode = JourneySearchMode.LEAVE_AT),
                    locale = Locale.ENGLISH,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_recommended)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_earlier_option)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_later_option)).assertExists()
    }

    @Test
    fun choicesRenderChronologicallyWithRecommendedExpandedByDefault() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(preview = readyPreview(), locale = Locale.ENGLISH, onRefresh = {})
            }
        }

        val earlierTop = composeRule.onNodeWithTag("planned-journey-card-earlier").fetchSemanticsNode().boundsInRoot.top
        val recommendedTop = composeRule.onNodeWithTag("planned-journey-card-recommended").fetchSemanticsNode().boundsInRoot.top
        val laterTop = composeRule.onNodeWithTag("planned-journey-card-later").fetchSemanticsNode().boundsInRoot.top
        assertTrue(earlierTop < recommendedTop && recommendedTop < laterTop)
        composeRule.onNodeWithText("Recommended interchange", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Earlier interchange", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Later interchange", substring = true).assertDoesNotExist()
    }

    @Test
    fun expandingAnotherChoiceCollapsesThePreviouslyExpandedCard() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(preview = readyPreview(), locale = Locale.ENGLISH, onRefresh = {})
            }
        }

        composeRule.onNodeWithTag("planned-journey-card-earlier").performClick()
        composeRule.onNodeWithText("Earlier interchange", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Recommended interchange", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("planned-journey-card-later").performClick()
        composeRule.onNodeWithText("Later interchange", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Earlier interchange", substring = true).assertDoesNotExist()
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
    fun aSingleRecommendedChoiceCreatesNoEmptyNeighborHeadings() {
        composeRule.setContent {
            BlickTheme {
                PlannedJourneySection(preview = readyPreview(onlyRecommended = true), locale = Locale.ENGLISH, onRefresh = {})
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_earlier_option)).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.one_time_event_plan_later_option)).assertDoesNotExist()
    }

    @Test
    fun swedishPlannedChoiceLabelsAreLocalized() {
        val configuration = Configuration(composeRule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("sv"))
        }
        val swedish = composeRule.activity.createConfigurationContext(configuration)

        assertEquals("Tidigare ankomst", swedish.getString(R.string.one_time_event_plan_earlier_option))
        assertEquals("Rekommenderad", swedish.getString(R.string.one_time_event_plan_recommended))
        assertEquals("Senare ankomst", swedish.getString(R.string.one_time_event_plan_later_option))
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
        searchMode: JourneySearchMode = JourneySearchMode.ARRIVE_BY,
        onlyRecommended: Boolean = false,
    ): PlannedJourneyPreviewState.Ready {
        fun journey(id: String, departure: String, arrival: String, interchange: String): JourneyPlan {
            val firstLeg = JourneyLeg(
                transportMode = TransportMode.METRO,
                lineDesignation = "19",
                direction = "Hagsätra",
                originName = "Home",
                destinationName = interchange,
                departureTime = Instant.parse(departure),
                arrivalTime = Instant.parse(departure).plusSeconds(15 * 60L),
                isRealtime = false,
                disruptions = emptyList(),
            )
            val secondLeg = JourneyLeg(
                transportMode = TransportMode.METRO,
                lineDesignation = "19",
                direction = "Hagsätra",
                originName = interchange,
                destinationName = "Globen",
                departureTime = requireNotNull(firstLeg.arrivalTime).plusSeconds(9 * 60L),
                arrivalTime = Instant.parse(arrival),
                isRealtime = false,
                disruptions = emptyList(),
            )
            return JourneyPlan(
                journeyId = id,
                originName = "Home",
                destinationName = "Globen",
                departureTime = requireNotNull(firstLeg.departureTime),
                arrivalTime = requireNotNull(secondLeg.arrivalTime),
                transferCount = 1,
                firstLeg = firstLeg,
                legs = listOf(firstLeg, secondLeg),
                disruptions = emptyList(),
            )
        }

        val earlier = journey("earlier", "2026-09-17T15:36:00Z", "2026-09-17T16:18:00Z", "Earlier interchange")
        val recommended = journey("recommended", "2026-09-17T15:46:00Z", "2026-09-17T16:28:00Z", "Recommended interchange")
        val later = journey("later", "2026-09-17T15:56:00Z", "2026-09-17T16:38:00Z", "Later interchange")
        val choices = if (onlyRecommended) {
            listOf(PlannedJourneyChoice(PlannedJourneyRole.RECOMMENDED, recommended))
        } else {
            // Deliberately non-chronological: the Event UI must follow departure time.
            listOf(
                PlannedJourneyChoice(PlannedJourneyRole.LATER, later),
                PlannedJourneyChoice(PlannedJourneyRole.EARLIER, earlier),
                PlannedJourneyChoice(PlannedJourneyRole.RECOMMENDED, recommended),
            )
        }
        return PlannedJourneyPreviewState.Ready(
            result = PlannedJourneyResult(
                fetchedAt = Instant.parse("2026-09-01T10:00:00Z"),
                searchMode = searchMode,
                requestedDateTime = Instant.parse("2026-09-17T16:30:00Z"),
                choices = choices,
            ),
        )
    }
}
