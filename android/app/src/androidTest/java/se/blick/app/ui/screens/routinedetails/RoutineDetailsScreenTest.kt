package se.blick.app.ui.screens.routinedetails

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.DisruptionRelevance
import se.blick.app.domain.model.DisruptionSource
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyDisruptionNotice
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.RoutineType
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.disruptionEffectLabelRes
import java.util.Locale

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

    @Before
    fun grantNotificationPermissionForDebugNotificationTests() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

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
        departureId: String = "d1",
        lineDesignation: String = "14",
        destination: String? = "T-Centralen",
        effectiveTime: Instant = Instant.parse("2026-08-02T07:05:00Z"),
        isRealTime: Boolean = false,
        isCancelled: Boolean = false,
    ) = PreparedDeparture(
        departureId = departureId,
        lineDesignation = lineDesignation,
        direction = "Northbound",
        destination = destination,
        scheduledTime = effectiveTime,
        expectedTime = effectiveTime.takeIf { isRealTime },
        effectiveTime = effectiveTime,
        minutesRemaining = 5,
        isRealTime = isRealTime,
        isCancelled = isCancelled,
        state = "EXPECTED",
        journeyState = "EXPECTED",
        predictionState = null,
        tripDeviations = emptyList(),
    )

    private fun sampleDepartures(count: Int = 5, prefix: String = "departure"): List<PreparedDeparture> =
        (1..count).map { index ->
            sampleDeparture(
                departureId = "$prefix-$index",
                destination = "Destination $index",
                effectiveTime = Instant.parse("2026-08-02T07:00:00Z").plusSeconds(index * 60L),
            )
        }

    private fun liveState(departures: List<PreparedDeparture>): LiveDeparturesState.Live =
        LiveDeparturesState.Live(
            LiveDeparturesSnapshot(
                departures = departures,
                fetchedAt = Instant.parse("2026-08-02T07:00:00Z"),
            ),
        )

    private fun setContent(
        disruptionsState: DisruptionsState,
        departuresState: LiveDeparturesState = LiveDeparturesState.Offline,
        routine: CommuteRoutine = sampleRoutine(),
        isPausedToday: Boolean = false,
        journeys: List<JourneyPlan> = emptyList(),
        exactDestinationDeviationNotices: List<ResolvedJourneyDisruption> = emptyList(),
        now: Instant = Instant.now(),
        onUpdateJourneyTransportModes: (Set<TransportMode>) -> Unit = {},
        isUpdatingChangesPreference: Boolean = false,
        changesPreferenceUpdateFailed: Boolean = false,
        onRefresh: () -> Unit = {},
    ) {
        composeRule.setContent {
            TestRoutineDetailsContent(
                disruptionsState = disruptionsState,
                departuresState = departuresState,
                routine = routine,
                isPausedToday = isPausedToday,
                journeys = journeys,
                exactDestinationDeviationNotices = exactDestinationDeviationNotices,
                now = now,
                onUpdateJourneyTransportModes = onUpdateJourneyTransportModes,
                isUpdatingChangesPreference = isUpdatingChangesPreference,
                changesPreferenceUpdateFailed = changesPreferenceUpdateFailed,
                onRefresh = onRefresh,
            )
        }
    }

    @Composable
    private fun TestRoutineDetailsContent(
        disruptionsState: DisruptionsState,
        departuresState: LiveDeparturesState = LiveDeparturesState.Offline,
        routine: CommuteRoutine = sampleRoutine(),
        isPausedToday: Boolean = false,
        journeys: List<JourneyPlan> = emptyList(),
        exactDestinationDeviationNotices: List<ResolvedJourneyDisruption> = emptyList(),
        now: Instant = Instant.now(),
        onUpdateJourneyTransportModes: (Set<TransportMode>) -> Unit = {},
        isUpdatingChangesPreference: Boolean = false,
        changesPreferenceUpdateFailed: Boolean = false,
        onRefresh: () -> Unit = {},
    ) {
        // Mirrors production: RoutineDetailsScreen's own uiState.routine is the single source
        // of truth for changes-preference updates. Keying by routine ID also lets the dynamic
        // routine-switch regression test model navigation to a different saved routine.
        var currentRoutine by remember(routine.id) { mutableStateOf(routine) }
        RoutineDetailsContent(
            modifier = Modifier,
            routine = currentRoutine,
            isPausedToday = isPausedToday,
            departuresState = departuresState,
            isRefreshing = false,
            disruptionsState = disruptionsState,
            journeys = journeys,
            exactDestinationDeviationNotices = exactDestinationDeviationNotices,
            now = now,
            onUpdateJourneyTransportModes = onUpdateJourneyTransportModes,
            isUpdatingChangesPreference = isUpdatingChangesPreference,
            changesPreferenceUpdateFailed = changesPreferenceUpdateFailed,
            onUpdateChangesPreference = { currentRoutine = currentRoutine.copy(changesPreference = it) },
            onRefresh = onRefresh,
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

    private fun heading(): String = composeRule.activity.getString(R.string.routine_details_disruptions_heading)

    private fun expandRoutineInfo() {
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_info_heading),
        ).performScrollTo().performClick()
    }

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
            substring = true,
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

        composeRule.onNodeWithText("in 5 min", substring = true).assertExists()
        composeRule.onNodeWithText("in 4 min", substring = true).assertDoesNotExist()

        // The SAME journeys list is never re-supplied -- only the evaluation timestamp advances,
        // exactly like a real automatic refresh whose journey response was structurally identical
        // but whose journeysEvaluatedAt still moved forward.
        composeRule.runOnIdle { now.value = Instant.parse("2026-07-28T08:00:30Z") }

        composeRule.onNodeWithText("in 4 min", substring = true).assertExists()
        composeRule.onNodeWithText("in 5 min", substring = true).assertDoesNotExist()
    }

    @Test
    fun countdownAtFiftyNineMinutesFiftyNineSecondsDisplaysOneHour() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val journey = journeyWithTransfers(
            id = "hour-boundary",
            lineDesignation = "577",
            transferCount = 0,
            departure = now.plusSeconds(59 * 60 + 59),
            durationMinutes = 20,
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(journey),
            now = now,
        )

        val oneHour = composeRule.activity.getString(R.string.journey_duration_hours, 1)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_departure_in, oneHour),
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("60 min", substring = true).assertDoesNotExist()
    }

    @Test
    fun longJourneyCardTimesDisplayHoursAndRemainingMinutes() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers(
            id = "primary",
            lineDesignation = "577",
            transferCount = 2,
            departure = now.plusSeconds(20 * 60),
            durationMinutes = 85,
            role = JourneyRole.PRIMARY,
        )
        val next = journeyWithTransfers(
            id = "next",
            lineDesignation = "577",
            transferCount = 2,
            departure = now.plusSeconds(139 * 60),
            durationMinutes = 88,
            role = JourneyRole.NEXT,
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary, next),
            now = now,
        )

        val departure = composeRule.activity.getString(R.string.journey_duration_hours_minutes, 2, 19)
        val later = composeRule.activity.getString(R.string.journey_duration_hours_minutes, 2, 2)
        val primaryDuration = composeRule.activity.getString(R.string.journey_duration_hours_minutes, 1, 25)
        val nextDuration = composeRule.activity.getString(R.string.journey_duration_hours_minutes, 1, 28)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_departure_in, departure),
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_later, later),
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_total_duration, primaryDuration),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_total_duration, nextDuration),
        ).assertExists()
    }

    // ---- Journeys section: Direct/With changes filters and per-card duration ----

    private fun exactDestinationRoutine(origin: String = "Fruängen", destination: String = "Mariatorget") =
        sampleRoutine().copy(
            name = "$origin → $destination",
            type = RoutineType.EXACT_DESTINATION,
            transportMode = TransportMode.UNKNOWN,
            lineId = null,
            lineDesignation = null,
            directionCode = null,
            destinationLabel = null,
            journeyOriginId = "origin-id",
            journeyOriginName = origin,
            journeyDestinationId = "destination-id",
            journeyDestinationName = destination,
        )

    private fun journeyWithTransfers(
        id: String,
        lineDesignation: String,
        transferCount: Int,
        departure: Instant,
        durationMinutes: Long,
        role: JourneyRole = JourneyRole.PRIMARY,
    ): JourneyPlan {
        val arrival = departure.plusSeconds(durationMinutes * 60)
        val leg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = lineDesignation,
            direction = "Direction",
            originName = "Origin",
            destinationName = "Destination",
            departureTime = departure,
            arrivalTime = arrival,
            isRealtime = true,
            disruptions = emptyList(),
        )
        return JourneyPlan(
            journeyId = id,
            originName = "Origin",
            destinationName = "Destination",
            departureTime = departure,
            arrivalTime = arrival,
            transferCount = transferCount,
            firstLeg = leg,
            legs = listOf(leg),
            disruptions = emptyList(),
            role = role,
        )
    }

    private fun timelineJourney(now: Instant): JourneyPlan {
        val firstDeparture = now.plusSeconds(5 * 60)
        val first = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "13",
            direction = "Ropsten",
            originName = "Mälarhöjden, Stockholm",
            destinationName = "Liljeholmen, Stockholm",
            departureTime = firstDeparture,
            arrivalTime = firstDeparture.plusSeconds(3 * 60),
            isRealtime = true,
            disruptions = emptyList(),
        )
        val second = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "14",
            direction = "Fruängen",
            originName = "Liljeholmen, Stockholm",
            destinationName = "Fruängen, Stockholm",
            departureTime = firstDeparture.plusSeconds(5 * 60),
            arrivalTime = firstDeparture.plusSeconds(16 * 60),
            isRealtime = true,
            disruptions = emptyList(),
        )
        return JourneyPlan(
            journeyId = "timeline",
            originName = first.originName,
            destinationName = second.destinationName,
            departureTime = firstDeparture,
            arrivalTime = second.arrivalTime!!,
            transferCount = 1,
            firstLeg = first,
            legs = listOf(first, second),
            disruptions = emptyList(),
            role = JourneyRole.PRIMARY,
        )
    }

    @Test
    fun exactDestinationRoutine_doesNotRepeatItsTopBarNameAsAContentHeading() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val journey = journeyWithTransfers("j1", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(origin = "Slussen", destination = "Fruängen"),
            journeys = listOf(journey),
            now = now,
        )

        // RoutineDetailsContent excludes the Scaffold/top bar. The route name belongs there,
        // while the collapsed Routine details section keeps its duplicate Route row hidden.
        composeRule.onNodeWithText("Slussen → Fruängen").assertDoesNotExist()
    }

    @Test
    fun bothFiltersAreSelectedByDefault_showingBothDirectAndWithChangesJourneys() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct, withChanges),
            now = now,
        )

        composeRule.onNodeWithText("14").assertExists()
        composeRule.onNodeWithText("40").assertExists()
    }

    @Test
    fun deselectingWithChanges_leavesOnlyDirectJourneysVisible() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct, withChanges),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_with_changes)).performClick()

        composeRule.onNodeWithText("14").assertExists()
        composeRule.onNodeWithText("40").assertDoesNotExist()
    }

    @Test
    fun directOnlyWithNoDirectJourneyAvailable_showsTheNoDirectMessage() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(withChanges),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_with_changes)).performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_no_direct_available)).assertExists()
        composeRule.onNodeWithText("40").assertDoesNotExist()
    }

    @Test
    fun deselectingDirect_leavesOnlyWithChangesJourneysVisible() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct, withChanges),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_direct)).performClick()

        composeRule.onNodeWithText("40").assertExists()
        composeRule.onNodeWithText("14").assertDoesNotExist()
    }

    @Test
    fun tappingTheOnlySelectedFilterIsANoOp_atLeastOneStaysSelected() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct),
            now = now,
        )

        // Deselect "With changes" first, leaving only Direct selected -- then try to deselect
        // Direct too. If that succeeded, the direct journey itself would disappear.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_with_changes)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_direct)).performClick()

        composeRule.onNodeWithText("14").assertExists()
    }

    // ---- Preference-update UI state: RoutineDetailsViewModel.isUpdatingChangesPreference /
    // changesPreferenceUpdateFailed guard the write itself (see that function's own doc), but the
    // chips must also visibly reflect an in-flight write and a failed one, not just silently
    // reject an overlapping tap. ----

    @Test
    fun whileAChangesPreferenceUpdateIsInFlight_bothFilterChipsAreDisabled() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct),
            now = now,
            isUpdatingChangesPreference = true,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_direct)).assertIsNotEnabled()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_with_changes)).assertIsNotEnabled()
    }

    @Test
    fun onceAChangesPreferenceUpdateFinishes_theFilterChipsAreEnabledAgain() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct),
            now = now,
            isUpdatingChangesPreference = false,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_direct)).assertIsEnabled()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_with_changes)).assertIsEnabled()
    }

    @Test
    fun changesPreferenceUpdateFailed_showsALocalizedErrorBelowTheChips() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct),
            now = now,
            changesPreferenceUpdateFailed = true,
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_changes_preference_update_failed),
        ).assertExists()
    }

    @Test
    fun noChangesPreferenceFailure_theErrorIsNotShown() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(direct),
            now = now,
            changesPreferenceUpdateFailed = false,
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_changes_preference_update_failed),
        ).assertDoesNotExist()
    }

    // ---- The persisted routine preference is the single source of truth for the chips' own
    // INITIAL selection, not merely what a tap changes it to -- see
    // se.blick.app.domain.model.ExactDestinationChangesPreference's own doc. Previously
    // impossible to observe: the pre-persistence design always started both chips selected
    // regardless of any per-routine value, since none existed. ----

    @Test
    fun aRoutinePersistedAsDirectOnly_startsWithOnlyDirectJourneysVisible_noTapNeeded() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine().copy(changesPreference = ExactDestinationChangesPreference.DIRECT_ONLY),
            journeys = listOf(direct, withChanges),
            now = now,
        )

        composeRule.onNodeWithText("14").assertExists()
        composeRule.onNodeWithText("40").assertDoesNotExist()
    }

    @Test
    fun aRoutinePersistedAsDirectOnly_withGenuinelyNoJourneysFound_showsTheNoDirectMessageNotTheGenericOne() {
        // A successful fetch that simply found nothing (currentJourneys itself empty, not just
        // filtered down to empty) previously fell straight into the generic "no journeys" branch
        // regardless of preference -- DIRECT_ONLY must get the same specific, actionable message
        // as the filtered-down case above, not the generic one.
        val now = Instant.parse("2026-08-13T08:00:00Z")

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine().copy(changesPreference = ExactDestinationChangesPreference.DIRECT_ONLY),
            journeys = emptyList(),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_no_direct_available)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.routine_details_no_journeys)).assertDoesNotExist()
    }

    @Test
    fun aRoutinePersistedAsWithChangesOnly_startsWithOnlyWithChangesJourneysVisible_noTapNeeded() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val direct = journeyWithTransfers("direct", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val withChanges = journeyWithTransfers("changes", "40", transferCount = 1, departure = now.plusSeconds(600), durationMinutes = 30)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine().copy(changesPreference = ExactDestinationChangesPreference.WITH_CHANGES_ONLY),
            journeys = listOf(direct, withChanges),
            now = now,
        )

        composeRule.onNodeWithText("40").assertExists()
        composeRule.onNodeWithText("14").assertDoesNotExist()
    }

    @Test
    fun eachCardShowsItsOwnTotalJourneyDuration() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val journey = journeyWithTransfers("j1", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 26)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(journey),
            now = now,
        )

        val durationText = composeRule.activity.getString(R.string.journey_duration_minutes, 26)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_total_duration, durationText),
        ).assertExists()
    }

    @Test
    fun expandedJourneyCard_showsStructuredTimelineAndCollapsesAgain() {
        val now = Instant.parse("2026-08-23T12:00:00Z")
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(timelineJourney(now)),
            now = now,
        )

        val changeAt = composeRule.activity.getString(R.string.journey_change_at, "Liljeholmen")
        composeRule.onNodeWithText(changeAt).assertDoesNotExist()

        val expand = composeRule.activity.getString(R.string.journey_expand)
        composeRule.onNodeWithContentDescription(expand).performScrollTo().performClick()

        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.journey_mode_line_format,
                composeRule.activity.getString(R.string.journey_mode_metro),
                "13",
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.journey_leg_route_format,
                "Mälarhöjden",
                "Liljeholmen",
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_toward_format, "Ropsten"),
        ).assertExists()
        composeRule.onNodeWithText(changeAt).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.journey_approximate_duration,
                composeRule.activity.getString(R.string.journey_duration_minutes, 2),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.journey_mode_line_format,
                composeRule.activity.getString(R.string.journey_mode_metro),
                "14",
            ),
        ).assertExists()

        val collapse = composeRule.activity.getString(R.string.journey_collapse)
        composeRule.onNodeWithContentDescription(collapse).performScrollTo().performClick()
        composeRule.onNodeWithText(changeAt).assertDoesNotExist()
    }

    @Test
    fun journeySummary_usesSingularChangeCopyAndAuthoritativeTotal() {
        val now = Instant.parse("2026-08-23T12:00:00Z")
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(timelineJourney(now)),
            now = now,
        )

        val oneChange = composeRule.activity.resources.getQuantityString(R.plurals.journey_changes, 1, 1)
        composeRule.onNodeWithText(oneChange, substring = true).assertExists()
        val duration = composeRule.activity.getString(R.string.journey_duration_minutes, 16)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.journey_total_duration, duration),
        ).assertExists()
    }

    // ---- Role-based card labels (PRIMARY/NEXT/ALTERNATIVE) -- labelled from each journey's own
    // role, never list position, so the Direct/With-changes filters above can never leave a
    // misleadingly-labelled card on screen ----

    @Test
    fun theSecondRegularDeparture_isLabelledNextNotAlternative() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers("primary", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20, role = JourneyRole.PRIMARY)
        val next = journeyWithTransfers("next", "14", transferCount = 0, departure = now.plusSeconds(900), durationMinutes = 20, role = JourneyRole.NEXT)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary, next),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_fastest)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_next)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_alternative)).assertDoesNotExist()
    }

    @Test
    fun aGenuineGapFillingAlternative_showsAllThreeCardsWithTheirOwnLabels() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers("primary", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20, role = JourneyRole.PRIMARY)
        val alternative = journeyWithTransfers("alt", "40", transferCount = 1, departure = now.plusSeconds(900), durationMinutes = 25, role = JourneyRole.ALTERNATIVE)
        val next = journeyWithTransfers("next", "14", transferCount = 0, departure = now.plusSeconds(3600), durationMinutes = 20, role = JourneyRole.NEXT)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary, alternative, next),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_fastest)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_alternative)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_next)).assertExists()
    }

    @Test
    fun filteringToWithChangesOnly_leavesTheAlternativeCardCorrectlyLabelled_notFastest() {
        // Regression test for "filtering cannot cause misleading role labels": a direct PRIMARY
        // plus a transfer ALTERNATIVE, filtered down to With-changes-only, leaves the
        // ALTERNATIVE as the sole (and therefore first-shown) card -- it must still say
        // "ALTERNATIVE", never default to "FASTEST" purely because it's now first on screen.
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers("primary", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20, role = JourneyRole.PRIMARY)
        val alternative = journeyWithTransfers("alt", "40", transferCount = 1, departure = now.plusSeconds(900), durationMinutes = 25, role = JourneyRole.ALTERNATIVE)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary, alternative),
            now = now,
        )

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_direct)).performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_alternative)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_fastest)).assertDoesNotExist()
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
        expandRoutineInfo()
        composeRule.onNodeWithText("14").assertExists()
    }

    @Test
    fun aRoutineWithNoLineDesignationShowsNoLineDetailRow() {
        setContent(DisruptionsState.NoDisruptions, routine = sampleRoutine().copy(lineDesignation = null))
        expandRoutineInfo()
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

    // ---- Ordinary line/direction departures: two visible by default, with a local-only
    // expansion to at most five rows from the same fetched snapshot. ----

    @Test
    fun fiveDepartures_showTwoByDefault_thenMoreAndShowFewerToggleLocally() {
        val departures = sampleDepartures()
        var refreshCount = 0
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(departures),
            onRefresh = { refreshCount++ },
        )

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-1")).assertExists()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-2")).assertExists()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-3")).assertDoesNotExist()
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        (3..5).forEach { index ->
            composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-$index")).assertExists()
        }
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_show_fewer_departures),
        ).assertExists()
        composeRule.runOnIdle { assertEquals(0, refreshCount) }

        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-3")).assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_more_departures),
        ).assertExists()
        composeRule.runOnIdle { assertEquals(0, refreshCount) }
    }

    @Test
    fun oneDeparture_hasNoMoreToggle() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(sampleDepartures(count = 1)),
        )

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-1")).assertExists()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-2")).assertDoesNotExist()
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG).assertDoesNotExist()
    }

    @Test
    fun twoDepartures_haveNoMoreToggle() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(sampleDepartures(count = 2)),
        )

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-1")).assertExists()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-2")).assertExists()
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG).assertDoesNotExist()
    }

    @Test
    fun expansion_isDefensivelyCappedAtFiveRows() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(sampleDepartures(count = 6)),
        )

        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()

        (1..5).forEach { index ->
            composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-$index")).assertExists()
        }
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-6")).assertDoesNotExist()
    }

    @Test
    fun staleSnapshot_canExpandWithoutLosingItsWarning() {
        val snapshot = liveState(sampleDepartures()).snapshot
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = LiveDeparturesState.Stale(snapshot),
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_stale_warning),
        ).assertExists()
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-5")).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_stale_warning),
        ).assertExists()
    }

    @Test
    fun expansion_survivesARefreshRecompositionForTheSameRoutine() {
        val departuresState = mutableStateOf<LiveDeparturesState>(liveState(sampleDepartures(prefix = "before")))
        composeRule.setContent {
            TestRoutineDetailsContent(
                disruptionsState = DisruptionsState.NoDisruptions,
                departuresState = departuresState.value,
            )
        }
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("before-5")).assertExists()

        composeRule.runOnIdle {
            departuresState.value = liveState(sampleDepartures(prefix = "after"))
        }

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("after-5")).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_show_fewer_departures),
        ).assertExists()
    }

    @Test
    fun expansion_resetsWhenADifferentRoutineIsShown() {
        val routineState = mutableStateOf(sampleRoutine())
        composeRule.setContent {
            TestRoutineDetailsContent(
                disruptionsState = DisruptionsState.NoDisruptions,
                departuresState = liveState(sampleDepartures()),
                routine = routineState.value,
            )
        }
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-5")).assertExists()

        composeRule.runOnIdle { routineState.value = sampleRoutine().copy(id = "r2") }

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-3")).assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_more_departures),
        ).assertExists()
    }

    @Test
    fun expansion_survivesSavedInstanceStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            TestRoutineDetailsContent(
                disruptionsState = DisruptionsState.NoDisruptions,
                departuresState = liveState(sampleDepartures()),
            )
        }
        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-5")).assertExists()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(routineDetailsDepartureRowTag("departure-5")).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_show_fewer_departures),
        ).assertExists()
    }

    @Test
    fun expansion_preservesLiveScheduledAndCancelledStatuses() {
        val departures = sampleDepartures().mapIndexed { index, departure ->
            when (index) {
                0 -> departure.copy(isRealTime = true, expectedTime = departure.effectiveTime)
                2 -> departure.copy(isCancelled = true)
                else -> departure
            }
        }
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(departures),
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_departure_live),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_departure_scheduled),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_departure_cancelled),
        ).assertDoesNotExist()

        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_departure_cancelled),
        ).assertExists()
    }

    @Test
    fun moreDeparturesToggle_hasAFullTouchTargetAndClickSemantics() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(sampleDepartures()),
        )

        val toggle = composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG)
            .performScrollTo()
            .assertHasClickAction()
        val height = toggle.fetchSemanticsNode().touchBoundsInRoot.height
        val minimumTouchHeight = 48f * composeRule.activity.resources.displayMetrics.density
        assertTrue("expected at least a 48dp touch target, was $height px", height >= minimumTouchHeight - 0.5f)
    }

    @Test
    fun exactDestinationRoutine_neverShowsTheLineDepartureExpansionControl() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = liveState(sampleDepartures()),
            routine = exactDestinationRoutine(),
        )

        composeRule.onNodeWithTag(ROUTINE_DETAILS_DEPARTURES_TOGGLE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_more_departures),
        ).assertDoesNotExist()
    }

    @Test
    fun departureExpansionStrings_areExactInEnglishAndSwedish() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        fun localizedContext(locale: Locale) = baseContext.createConfigurationContext(
            Configuration(baseContext.resources.configuration).apply { setLocale(locale) },
        )

        val english = localizedContext(Locale.ENGLISH)
        assertEquals("More departures", english.getString(R.string.routine_details_more_departures))
        assertEquals("Show fewer", english.getString(R.string.routine_details_show_fewer_departures))

        val swedish = localizedContext(Locale.forLanguageTag("sv"))
        assertEquals("Fler avgångar", swedish.getString(R.string.routine_details_more_departures))
        assertEquals("Visa färre", swedish.getString(R.string.routine_details_show_fewer_departures))
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
    fun refreshAndPauseTodayButtons_shareAnEqualWidthRow() {
        val snapshot = LiveDeparturesSnapshot(
            departures = listOf(sampleDeparture()),
            fetchedAt = Instant.parse("2026-08-02T07:00:00Z"),
        )
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = LiveDeparturesState.Live(snapshot),
            isPausedToday = false,
        )

        val refresh = composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_refresh_action),
        )
        val pause = composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_pause_today_action),
        )
        pause.performScrollTo()

        val refreshBounds = refresh.fetchSemanticsNode().boundsInRoot
        val pauseBounds = pause.fetchSemanticsNode().boundsInRoot
        assertEquals(refreshBounds.top, pauseBounds.top, 1f)
        assertEquals(refreshBounds.width, pauseBounds.width, 1f)
    }

    @Test
    fun noUpcomingDepartures_showsOnlyThePrimaryRefreshButton() {
        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            departuresState = LiveDeparturesState.NoUpcomingDepartures(Instant.parse("2026-08-02T07:00:00Z")),
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_no_departures),
        ).assertExists()
        composeRule.onAllNodesWithText(
            composeRule.activity.getString(R.string.routine_details_refresh_action),
        ).assertCountEquals(1)
    }

    @Test
    fun pauseTodayButton_showsResumeTodayWhenAlreadyPausedToday() {
        setContent(DisruptionsState.NoDisruptions, isPausedToday = true)

        val resumeLabel = composeRule.activity.getString(R.string.routine_details_resume_today_action)
        val pauseLabel = composeRule.activity.getString(R.string.routine_details_pause_today_action)
        composeRule.onNodeWithText(resumeLabel).assertExists()
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()
    }

    // ---- Routine details -- collapsed by default, with the existing rows revealed by the
    // same whole-header tap pattern used by Manage routine below ----

    @Test
    fun routineDetailsSection_collapsedByDefault_showsOnlyItsHeading() {
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_info_heading)
        val schedule = composeRule.activity.getString(R.string.routine_details_schedule_label)
        val status = composeRule.activity.getString(R.string.routine_details_status_label)
        composeRule.onNodeWithText(heading).performScrollTo().assertExists()
        composeRule.onNodeWithText(schedule).assertDoesNotExist()
        composeRule.onNodeWithText(status).assertDoesNotExist()
    }

    @Test
    fun routineDetailsSection_tappingHeaderRevealsAndHidesExistingRows() {
        setContent(DisruptionsState.NoDisruptions)

        val heading = composeRule.activity.getString(R.string.routine_details_info_heading)
        val schedule = composeRule.activity.getString(R.string.routine_details_schedule_label)
        val status = composeRule.activity.getString(R.string.routine_details_status_label)
        composeRule.onNodeWithText(heading).performScrollTo().performClick()
        composeRule.onNodeWithText(schedule).assertExists()
        composeRule.onNodeWithText(status).assertExists()

        composeRule.onNodeWithText(heading).performScrollTo().performClick()
        composeRule.onNodeWithText(schedule).assertDoesNotExist()
        composeRule.onNodeWithText(status).assertDoesNotExist()
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
        expandRoutineInfo()

        val routeLabel = composeRule.activity.getString(R.string.routine_details_route_label)
        composeRule.onNodeWithText(routeLabel).performScrollTo().assertExists()
        // The route is shown once in this expanded detail row. RoutineDetailsContent excludes
        // the top app bar, and the journeys section intentionally does not repeat that title.
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
        expandRoutineInfo()

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

    // ---- Exact-destination disruption relevance: the top Disruptions section is driven ENTIRELY
    // by exactDestinationDeviationNotices -- the backend's own already-resolved, deduplicated
    // list (see RoutineDetailsContent's own doc) -- never by journeys' own raw disruptionNotices
    // directly (those only feed the FIRST, primary-only notification/widget post; the screen's
    // own section always reflects the fully resolved result). A CONFIRMED entry shows its real SL
    // text with no qualifier; a LINE_RELEVANT entry shows the same real text PLUS the "not
    // confirmed for this exact journey" caption (the Akalla -> T-Centralen false-positive fix).
    // The existing expanded leg.disruptions rendering (raw SL text, unrelated to this feature) is
    // unaffected. ----

    private fun resolvedDisruption(
        id: String? = "d1",
        headline: String = "Hissen är ur funktion.",
        details: String? = null,
        effect: DisruptionEffect = DisruptionEffect.ACCESSIBILITY_ISSUE,
        relevance: DisruptionRelevance = DisruptionRelevance.CONFIRMED,
        source: DisruptionSource = DisruptionSource.JOURNEY_PLANNER,
        matchedLineDesignations: List<String> = emptyList(),
    ) = ResolvedJourneyDisruption(id, headline, details, effect, relevance, source, matchedLineDesignations)

    @Test
    fun exactDestinationRoutine_topDisruptionsSection_showsConfirmedResolvedDisruption() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers("primary", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary),
            exactDestinationDeviationNotices = listOf(resolvedDisruption()),
            now = now,
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_disruptions_heading_exact_destination),
        ).assertExists()
        composeRule.onNodeWithText("⚠️ Hissen är ur funktion.").assertExists()
        // CONFIRMED -- the "not confirmed for this exact journey" qualifier must never appear.
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_disruption_line_relevant_qualifier, "11"),
        ).assertDoesNotExist()
    }

    @Test
    fun exactDestinationRoutine_topDisruptionsSection_hiddenWhenThereIsNoResolvedDisruption() {
        val now = Instant.parse("2026-08-13T08:00:00Z")
        // PRIMARY's own journeys.disruptionNotices is irrelevant to this section now -- only
        // exactDestinationDeviationNotices (empty here) decides whether it renders at all.
        val primary = journeyWithTransfers("primary", "14", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
            .copy(disruptionNotices = listOf(JourneyDisruptionNotice("Bussen är omledd.", DisruptionEffect.ROUTE_CHANGE)))

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary),
            now = now,
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_disruptions_heading_exact_destination),
        ).assertDoesNotExist()
        composeRule.onNodeWithText("⚠️ Bussen är omledd.").assertDoesNotExist()
    }

    @Test
    fun exactDestinationRoutine_topDisruptionsSection_lineRelevantDisruptionShowsRealTextPlusQualifier() {
        // The Akalla -> T-Centralen false-positive fix: an SL Deviation whose line/mode scope
        // matched PRIMARY, but whose affected segment/stop was not proven to intersect this exact
        // journey, must still show the real SL text (Routine Details never hides it) but with a
        // caption making clear it is not confirmed for this exact journey.
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val primary = journeyWithTransfers("primary", "11", transferCount = 0, departure = now.plusSeconds(300), durationMinutes = 20)
        val lineRelevant = resolvedDisruption(
            id = "akalla-dev-1",
            headline = "Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården",
            effect = DisruptionEffect.NO_SERVICE,
            relevance = DisruptionRelevance.LINE_RELEVANT,
            source = DisruptionSource.SL_DEVIATIONS,
            matchedLineDesignations = listOf("11"),
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(primary),
            exactDestinationDeviationNotices = listOf(lineRelevant),
            now = now,
        )

        composeRule.onNodeWithText("⚠️ Inställd trafik på Blå linjen mellan T-Centralen och Kungsträdgården").assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.routine_details_disruption_line_relevant_qualifier, "11"),
        ).assertExists()
    }

    @Test
    fun exactDestinationRoutine_expandedCard_stillRendersRawLegDisruptionText() {
        // Regression check: the pre-existing expanded-journey-card leg.disruptions rendering (raw
        // SL text, a completely different code path from the top Disruptions section above) must
        // be unaffected by this feature.
        val now = Instant.parse("2026-08-13T08:00:00Z")
        val leg = JourneyLeg(
            transportMode = TransportMode.METRO,
            lineDesignation = "14",
            direction = "Direction",
            originName = "Origin",
            destinationName = "Destination",
            departureTime = now.plusSeconds(300),
            arrivalTime = now.plusSeconds(300 + 1200),
            isRealtime = true,
            disruptions = listOf("Lift unavailable at Origin"),
        )
        val journey = JourneyPlan(
            journeyId = "j1",
            originName = "Origin",
            destinationName = "Destination",
            departureTime = now.plusSeconds(300),
            arrivalTime = now.plusSeconds(300 + 1200),
            transferCount = 0,
            firstLeg = leg,
            legs = listOf(leg),
            disruptions = listOf("Lift unavailable at Origin"),
        )

        setContent(
            disruptionsState = DisruptionsState.NoDisruptions,
            routine = exactDestinationRoutine(),
            journeys = listOf(journey),
            now = now,
        )

        // Collapsed by default -- tap the card (its own role label, the only journey here so
        // it's FASTEST) to expand it.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.journey_fastest)).performClick()
        composeRule.onNodeWithText("Lift unavailable at Origin").assertExists()
    }

    // ---- Debug disruption-effect picker: chips exist for all nine DisruptionEffect values, and
    // tapping one then "Show test notification" posts through onShowDebugNotificationForEffect
    // with that exact effect -- see RoutineDetailsViewModel.showDebugTestNotification's own doc.
    // (androidTest runs against the debug build type, so BuildConfig.DEBUG is true here, exactly
    // like a real debug install -- this is not itself testing release-variant absence, which is
    // a structural, compile-time guarantee -- see DebugDisruptionSampleSource's own doc.) ----

    @Test
    fun debugDisruptionPicker_showsAChipForEveryEffectPlusTheRealOption() {
        setContent(DisruptionsState.NoDisruptions)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.debug_disruption_effect_real))
            .performScrollTo().assertExists()
        DisruptionEffect.entries.forEach { effect ->
            composeRule.onNodeWithText(composeRule.activity.getString(disruptionEffectLabelRes(effect)))
                .performScrollTo().assertExists()
        }
    }

    @Test
    fun debugDisruptionPicker_selectingAnEffectAndTappingShow_postsThatExactEffect() {
        val requestedEffects = mutableListOf<DisruptionEffect>()
        composeRule.setContent {
            RoutineDetailsContent(
                modifier = Modifier,
                routine = sampleRoutine(),
                isPausedToday = false,
                departuresState = LiveDeparturesState.Offline,
                isRefreshing = false,
                disruptionsState = DisruptionsState.NoDisruptions,
                now = Instant.now(),
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
                onShowDebugNotificationForEffect = { effect -> requestedEffects += effect; null },
                onRemoveDebugNotification = {},
                isLiveUpdatePromotable = { false },
            )
        }

        val delaysLabel = composeRule.activity.getString(R.string.notification_disruption_effect_delays)
        val showLabel = composeRule.activity.getString(R.string.debug_show_test_notification)
        val delaysChip = composeRule.onNodeWithText(delaysLabel)
        delaysChip.performScrollTo().performClick()
        delaysChip.assertIsSelected()
        composeRule.onNodeWithText(showLabel).performScrollTo().performClick()

        assertEquals(listOf(DisruptionEffect.DELAYS), requestedEffects)
    }

    @Test
    fun debugDisruptionPicker_reselectingRealAfterAnEffect_postsTheRealDisruptionAgain() {
        val requestedEffects = mutableListOf<DisruptionEffect>()
        var realPostCount = 0
        composeRule.setContent {
            RoutineDetailsContent(
                modifier = Modifier,
                routine = sampleRoutine(),
                isPausedToday = false,
                departuresState = LiveDeparturesState.Offline,
                isRefreshing = false,
                disruptionsState = DisruptionsState.NoDisruptions,
                now = Instant.now(),
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
                onShowDebugNotification = { realPostCount++; null },
                onShowDebugNotificationForEffect = { effect -> requestedEffects += effect; null },
                onRemoveDebugNotification = {},
                isLiveUpdatePromotable = { false },
            )
        }

        val delaysLabel = composeRule.activity.getString(R.string.notification_disruption_effect_delays)
        val realLabel = composeRule.activity.getString(R.string.debug_disruption_effect_real)
        val showLabel = composeRule.activity.getString(R.string.debug_show_test_notification)
        val delaysChip = composeRule.onNodeWithText(delaysLabel)
        delaysChip.performScrollTo().performClick()
        delaysChip.assertIsSelected()
        val realChip = composeRule.onNodeWithText(realLabel)
        realChip.performScrollTo().performClick()
        realChip.assertIsSelected()
        composeRule.onNodeWithText(showLabel).performScrollTo().performClick()

        assertEquals(emptyList<DisruptionEffect>(), requestedEffects)
        assertEquals(1, realPostCount)
    }
}
