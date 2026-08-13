package se.blick.app.ui.screens.routinedetails

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
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

    private fun sampleDeparture(
        lineDesignation: String = "14",
        destination: String? = "T-Centralen",
    ) = PreparedDeparture(
        departureId = "d1",
        lineDesignation = lineDesignation,
        direction = "Northbound",
        destination = destination,
        scheduledTime = Instant.parse("2026-08-02T07:05:00Z"),
        expectedTime = null,
        effectiveTime = Instant.parse("2026-08-02T07:05:00Z"),
        minutesRemaining = 5,
        isRealTime = false,
        isCancelled = false,
        state = "EXPECTED",
        journeyState = "EXPECTED",
        predictionState = null,
        tripDeviations = emptyList(),
    )

    private fun setContent(
        disruptionsState: DisruptionsState,
        departuresState: LiveDeparturesState = LiveDeparturesState.Offline,
        routine: CommuteRoutine = sampleRoutine(),
        isPausedToday: Boolean = false,
        journeys: List<JourneyPlan> = emptyList(),
        now: Instant = Instant.now(),
        onUpdateJourneyTransportModes: (Set<TransportMode>) -> Unit = {},
    ) {
        composeRule.setContent {
            RoutineDetailsContent(
                modifier = Modifier,
                routine = routine,
                isPausedToday = isPausedToday,
                departuresState = departuresState,
                isRefreshing = false,
                disruptionsState = disruptionsState,
                journeys = journeys,
                now = now,
                onUpdateJourneyTransportModes = onUpdateJourneyTransportModes,
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
                schedulingFailed = false,
                isRetryingScheduling = false,
                onRetryScheduling = {},
                onShowDebugNotification = { null },
                onRemoveDebugNotification = {},
                isLiveUpdatePromotable = { false },
            )
        }
    }

    private fun heading(): String = composeRule.activity.getString(R.string.routine_details_disruptions_heading)

    @Test
    fun journeyHeader_showsTransportTypeAfterLineNumber() {
        val departureTime = Instant.now().plusSeconds(5 * 60)
        val arrivalTime = departureTime.plusSeconds(18 * 60)
        val leg = JourneyLeg(
            transportMode = TransportMode.TRAIN,
            lineDesignation = "43",
            direction = "Balsta",
            originName = "Stockholm City",
            destinationName = "Sundbyberg",
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            isRealtime = true,
            disruptions = emptyList(),
        )
        val journey = JourneyPlan(
            journeyId = "journey-43",
            originName = leg.originName,
            destinationName = leg.destinationName,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            transferCount = 0,
            firstLeg = leg,
            legs = listOf(leg),
            disruptions = emptyList(),
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = sampleRoutine().copy(
                type = RoutineType.EXACT_DESTINATION,
                journeyOriginId = "origin-id",
                journeyOriginName = journey.originName,
                journeyDestinationId = "destination-id",
                journeyDestinationName = journey.destinationName,
            ),
            journeys = listOf(journey),
        )

        composeRule.onNodeWithText("43").assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_mode_commuter_rail),
        ).assertExists()
    }

    @Test
    fun anExpiredJourneyIsNeverRenderedAsFastestWithACountdown() {
        // Deterministic, not Instant.now()-relative -- the journey departed one second before
        // the fixed `now` this test supplies to setContent, proving the render-time filter reads
        // that supplied timestamp rather than depending on the real system clock.
        val now = Instant.parse("2026-08-10T22:12:00Z")
        val departureTime = now.minusSeconds(1)
        val arrivalTime = departureTime.plusSeconds(15 * 60)
        val leg = JourneyLeg(
            transportMode = TransportMode.BUS,
            lineDesignation = "57",
            direction = "Norsborg",
            originName = "Slussen",
            destinationName = "Norsborg",
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            isRealtime = true,
            disruptions = emptyList(),
        )
        val expiredJourney = JourneyPlan(
            journeyId = "journey-expired",
            originName = leg.originName,
            destinationName = leg.destinationName,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            transferCount = 0,
            firstLeg = leg,
            legs = listOf(leg),
            disruptions = emptyList(),
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = sampleRoutine().copy(
                type = RoutineType.EXACT_DESTINATION,
                journeyOriginId = "origin-id",
                journeyOriginName = expiredJourney.originName,
                journeyDestinationId = "destination-id",
                journeyDestinationName = expiredJourney.destinationName,
            ),
            journeys = listOf(expiredJourney),
            now = now,
        )

        // No card, no line badge, no "FASTEST" label, no countdown for the expired journey --
        // only the existing no-journeys state.
        composeRule.onNodeWithText("57").assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_fastest)).assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_no_journeys),
        ).assertExists()
    }

    @Test
    fun countdownAdvancesWhenOnlyTheEvaluationTimestampChangesForAnIdenticalJourneyList() {
        // Mirrors RoutineDetailsUiState.journeysEvaluatedAt's exact boundary: a journey departing
        // at 08:04:30 reads "in 5 min" when evaluated at 08:00:00, and "in 4 min" once evaluated
        // at 08:00:30 -- for the SAME journeys list object, proving the countdown genuinely comes
        // from the `now` parameter recomposing, not from the journey list itself changing.
        val departureTime = Instant.parse("2026-07-28T08:04:30Z")
        val arrivalTime = departureTime.plusSeconds(900)
        val leg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "14",
            direction = "Arlanda",
            originName = "Fruängen",
            destinationName = "Arlanda",
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            isRealtime = true,
            disruptions = emptyList(),
        )
        val journeys = listOf(
            JourneyPlan(
                journeyId = "journey-1",
                originName = leg.originName,
                destinationName = leg.destinationName,
                departureTime = departureTime,
                arrivalTime = arrivalTime,
                transferCount = 0,
                firstLeg = leg,
                legs = listOf(leg),
                disruptions = emptyList(),
            ),
        )
        val routine = sampleRoutine().copy(
            type = RoutineType.EXACT_DESTINATION,
            journeyOriginId = "origin-id",
            journeyOriginName = leg.originName,
            journeyDestinationId = "destination-id",
            journeyDestinationName = leg.destinationName,
        )
        lateinit var now: MutableState<Instant>

        composeRule.setContent {
            now = remember { mutableStateOf(Instant.parse("2026-07-28T08:00:00Z")) }
            RoutineDetailsContent(
                modifier = Modifier,
                routine = routine,
                isPausedToday = false,
                departuresState = LiveDeparturesState.Offline,
                isRefreshing = false,
                disruptionsState = DisruptionsState.NoDisruptions,
                journeys = journeys,
                now = now.value,
                onUpdateJourneyTransportModes = {},
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
                schedulingFailed = false,
                isRetryingScheduling = false,
                onRetryScheduling = {},
                onShowDebugNotification = { null },
                onRemoveDebugNotification = {},
                isLiveUpdatePromotable = { false },
            )
        }

        composeRule.onNodeWithText("in 5 min").assertExists()
        composeRule.onNodeWithText("in 4 min").assertDoesNotExist()

        // The SAME journeys list is never re-supplied -- only the evaluation timestamp advances,
        // exactly like a real automatic refresh whose journey response was structurally identical
        // but whose journeysEvaluatedAt still moved forward.
        composeRule.runOnIdle { now.value = Instant.parse("2026-07-28T08:00:30Z") }

        composeRule.onNodeWithText("in 4 min").assertExists()
        composeRule.onNodeWithText("in 5 min").assertDoesNotExist()
    }

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
        // substring = true -- the rendered header is prefixed with a "⚠️ " warning symbol.
        composeRule.onNodeWithText(d.message.header, substring = true).assertExists()
        composeRule.onNodeWithText(d.message.details).assertDoesNotExist()
    }

    @Test
    fun loaded_expandingRevealsTheDetails() {
        val d = disruption()
        setContent(DisruptionsState.Loaded(listOf(d)))

        // The disruptions section is the first thing in RoutineDetailsContent's scrollable
        // Column, so this button is normally already on-screen -- performScrollTo() is kept
        // anyway as a defensive no-op, since a click at an off-screen coordinate would otherwise
        // be silently ignored on a real device rather than throwing.
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

        // substring = true -- the rendered header is prefixed with a "⚠️ " warning symbol.
        composeRule.onNodeWithText(first.message.header, substring = true).assertExists()
        composeRule.onNodeWithText(second.message.header, substring = true).assertExists()
    }

    // ---- Shared line-number badge (see se.blick.app.ui.components.LineBadge) — the same
    // colored badge used on route selection, the routine list, departure rows, and the
    // home-screen widget ----

    @Test
    fun theRoutineHeaderShowsTheSharedLineBadgeForItsOwnLine() {
        // sampleRoutine() has lineDesignation = "14".
        setContent(DisruptionsState.NoDisruptions)
        composeRule.onNodeWithText("14").assertExists()
    }

    @Test
    fun aRoutineWithNoLineDesignationShowsNoLineDetailRow() {
        setContent(DisruptionsState.NoDisruptions, routine = sampleRoutine().copy(lineDesignation = null))
        val lineLabel = composeRule.activity.getString(R.string.routine_details_line_label)
        composeRule.onNodeWithText(lineLabel).assertDoesNotExist()
    }

    @Test
    fun eachDepartureRowShowsTheSharedLineBadgeForItsOwnLine() {
        // A different line AND destination than the routine's own ("14"/"T-Centralen",
        // asserted separately above -- sampleRoutine()'s own destinationLabel already renders
        // its own "T-Centralen" text elsewhere on this screen) so this assertion unambiguously
        // targets the departure row's own badge/text, not the header's.
        val departure = sampleDeparture(lineDesignation = "18", destination = "Farsta strand")
        val snapshot = LiveDeparturesSnapshot(departures = listOf(departure), fetchedAt = Instant.parse("2026-08-02T07:00:00Z"))
        setContent(DisruptionsState.NoDisruptions, departuresState = LiveDeparturesState.Live(snapshot))

        composeRule.onNodeWithText("18").assertExists()
        composeRule.onNodeWithText(departure.destination!!).assertExists()
    }

    // ---- Pause/resume today -- now placed directly under the departures list, independent of
    // the (collapsed-by-default) Manage routine section below it ----

    @Test
    fun pauseTodayButton_visibleWithoutExpandingManageRoutine() {
        setContent(DisruptionsState.NoDisruptions, isPausedToday = false)

        val pauseLabel = composeRule.activity.getString(R.string.routine_details_pause_today_action)
        composeRule.onNodeWithText(pauseLabel).assertExists()
    }

    @Test
    fun pauseTodayButton_showsResumeTodayWhenAlreadyPausedToday() {
        setContent(DisruptionsState.NoDisruptions, isPausedToday = true)

        val resumeLabel = composeRule.activity.getString(R.string.routine_details_resume_today_action)
        val pauseLabel = composeRule.activity.getString(R.string.routine_details_pause_today_action)
        composeRule.onNodeWithText(resumeLabel).assertExists()
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()
    }

    // ---- Manage routine -- collapsed by default, expands on tapping anywhere across the
    // heading+description header, same collapsed-header/expand-on-tap shape as the disruptions
    // section above ----

    @Test
    fun manageRoutineSection_collapsedByDefault_showsOnlyHeadingAndDescription() {
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_actions_heading)
        val description = composeRule.activity.getString(R.string.routine_details_actions_description)
        val editLabel = composeRule.activity.getString(R.string.routine_details_edit_action)
        val deleteLabel = composeRule.activity.getString(R.string.routine_details_delete_action)
        composeRule.onNodeWithText(heading).performScrollTo().assertExists()
        composeRule.onNodeWithText(description).assertExists()
        composeRule.onNodeWithText(editLabel).assertDoesNotExist()
        composeRule.onNodeWithText(deleteLabel).assertDoesNotExist()
    }

    @Test
    fun manageRoutineSection_tappingTheHeaderExpandsToRevealTheActions() {
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_actions_heading)
        composeRule.onNodeWithText(heading).performScrollTo().performClick()

        val editLabel = composeRule.activity.getString(R.string.routine_details_edit_action)
        val disableLabel = composeRule.activity.getString(R.string.routine_details_disable_action)
        val deleteLabel = composeRule.activity.getString(R.string.routine_details_delete_action)
        composeRule.onNodeWithText(editLabel).assertExists()
        composeRule.onNodeWithText(disableLabel).assertExists()
        composeRule.onNodeWithText(deleteLabel).assertExists()
    }

    @Test
    fun exactDestinationRoutine_manageSectionIncludesEditAction() {
        val exactRoutine = sampleRoutine().copy(
            type = RoutineType.EXACT_DESTINATION,
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin-id",
            journeyOriginName = "Fruängen",
            journeyDestinationId = "destination-id",
            journeyDestinationName = "Mariatorget",
        )
        setContent(DisruptionsState.NoDisruptions, routine = exactRoutine)

        val heading = composeRule.activity.getString(R.string.routine_details_actions_heading)
        composeRule.onNodeWithText(heading).performScrollTo().performClick()

        val editLabel = composeRule.activity.getString(R.string.routine_details_edit_action)
        composeRule.onNodeWithText(editLabel).assertExists()
    }

    @Test
    fun exactDestinationRoutine_showsRouteRowWithOriginAndDestination() {
        // Regression test: exact-destination routines never populate destinationLabel (a
        // LINE_DIRECTION-only field), so the old single "Direction" row silently rendered
        // nothing for them -- the Route row below is what now replaces it for this type.
        val exactRoutine = sampleRoutine().copy(
            type = RoutineType.EXACT_DESTINATION,
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin-id",
            journeyOriginName = "Fruängen",
            journeyDestinationId = "destination-id",
            journeyDestinationName = "Mariatorget",
        )
        setContent(DisruptionsState.NoDisruptions, routine = exactRoutine)

        val routeLabel = composeRule.activity.getString(R.string.routine_details_route_label)
        composeRule.onNodeWithText(routeLabel).performScrollTo().assertExists()
        composeRule.onNodeWithText("Fruängen → Mariatorget").assertExists()

        val directionLabel = composeRule.activity.getString(R.string.routine_details_direction_label)
        composeRule.onNodeWithText(directionLabel).assertDoesNotExist()
    }

    @Test
    fun exactDestinationTransportPlusAllowsAddingAndRemovingModes() {
        var savedModes: Set<TransportMode>? = null
        val exactRoutine = sampleRoutine().copy(
            type = RoutineType.EXACT_DESTINATION,
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin-id",
            journeyOriginName = "Fruängen",
            journeyDestinationId = "destination-id",
            journeyDestinationName = "Mariatorget",
            allowedJourneyTransportModes = setOf(TransportMode.METRO, TransportMode.BUS),
        )
        setContent(
            DisruptionsState.NoDisruptions,
            routine = exactRoutine,
            onUpdateJourneyTransportModes = { savedModes = it },
        )

        val metro = composeRule.activity.getString(R.string.transport_mode_metro)
        val bus = composeRule.activity.getString(R.string.transport_mode_bus)
        composeRule.onNodeWithText("$metro, $bus").performScrollTo().assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.transport_mode_unknown)).assertDoesNotExist()

        val changeLabel = composeRule.activity.getString(R.string.routine_details_transport_change)
        composeRule.onNodeWithContentDescription(changeLabel).performScrollTo().performClick()

        val train = composeRule.activity.getString(R.string.transport_mode_train)
        composeRule.onNodeWithText(metro).performClick()
        composeRule.onNodeWithText(train).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_save)).performClick()

        assertEquals(setOf(TransportMode.TRAIN, TransportMode.BUS), savedModes)
    }

    @Test
    fun manageRoutineSection_tappingTheHeaderAgainCollapsesItBackToJustHeadingAndDescription() {
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_actions_heading)
        composeRule.onNodeWithText(heading).performScrollTo().performClick()
        composeRule.onNodeWithText(heading).performScrollTo().performClick()

        val editLabel = composeRule.activity.getString(R.string.routine_details_edit_action)
        composeRule.onNodeWithText(editLabel).assertDoesNotExist()
    }

    @Test
    fun manageRoutineSection_pauseTodayIsNotAmongTheRevealedActions() {
        // Confirms the relocation, not just the addition -- pause/resume must never reappear a
        // second time inside the expanded Manage routine group now that PauseTodayButton owns it.
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_actions_heading)
        composeRule.onNodeWithText(heading).performScrollTo().performClick()

        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.routine_details_pause_today_action))
            .assertCountEquals(1)
    }
}
