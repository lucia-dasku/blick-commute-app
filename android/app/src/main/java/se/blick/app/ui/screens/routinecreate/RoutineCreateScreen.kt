@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinecreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.data.repository.DirectionOption
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.components.BlickWizardHeader
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.notification.rememberNotificationPermissionGate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * Real setup flow, per the product doc's "Initial setup" section and per
 * docs/api-contract.md §10: stop -> transport mode -> line/direction -> weekdays -> time
 * window (+ name), saved as a [se.blick.app.domain.model.CommuteRoutine].
 * [se.blick.app.data.repository.DirectionOptionsSource] already conflates line+direction
 * into one selectable option, so this wizard has one combined step for both rather than
 * two separate ones.
 */
@Composable
fun RoutineCreateScreen(
    onDone: () -> Unit,
    // Defaulted so this screen keeps compiling for any test/preview call site written before
    // the premium upsell existed — see OriginDestinationStep's own doc on where this is
    // actually used.
    onOpenPremium: () -> Unit = {},
    viewModel: RoutineCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Shared by the toolbar's back arrow AND the system Back button/gesture, so both
    // navigate the wizard step-by-step identically instead of the system gesture exiting
    // the whole screen regardless of step (see the 2026-07-28 review).
    val handleBack: () -> Unit = { if (!viewModel.back()) onDone() }
    BackHandler(onBack = handleBack)

    // Edit mode reuses this exact wizard (see RoutineCreateViewModel's class doc) but has
    // three states the plain creation flow never does: still loading the existing routine,
    // the navigated-to routine id no longer resolving to anything, and (create mode only)
    // the first-beta one-routine limit blocking the flow outright. All three fully replace
    // the wizard content below rather than trying to render alongside it.
    val isBlocked = uiState.isLoadingExistingRoutine || uiState.existingRoutineNotFound ||
        (uiState.oneRoutineLimitReached && !uiState.isEditMode)

    Scaffold(
        topBar = {
            if (isBlocked) {
                BlickTopBar(title = stringResource(R.string.routine_create_title), onBack = handleBack)
            } else {
                val totalSteps = if (uiState.isExactDestination) 2 else 4
                val stepNumber = when (uiState.step) {
                    RoutineCreateStep.STOP -> 1
                    RoutineCreateStep.TRANSPORT_MODE -> 2
                    RoutineCreateStep.DIRECTION -> 3
                    RoutineCreateStep.SCHEDULE -> if (uiState.isExactDestination) 2 else 4
                }
                BlickWizardHeader(
                    title = stepTitle(uiState.step),
                    stepNumber = stepNumber,
                    totalSteps = totalSteps,
                    progress = stepNumber / totalSteps.toFloat(),
                    onBack = handleBack,
                )
            }
        },
    ) { padding ->
        when {
            uiState.isLoadingExistingRoutine -> CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator()
            }
            uiState.existingRoutineNotFound -> CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                BlockedMessage(
                    text = stringResource(R.string.routine_create_existing_not_found),
                    onDone = onDone,
                )
            }
            uiState.oneRoutineLimitReached && !uiState.isEditMode -> CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                BlockedMessage(
                    text = stringResource(R.string.routine_create_limit_reached),
                    onDone = onDone,
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (uiState.step) {
                        RoutineCreateStep.STOP -> OriginDestinationStep(
                            uiState = uiState,
                            onQueryChanged = viewModel::onSiteQueryChanged,
                            onSelectSite = viewModel::selectOrigin,
                            onDestinationQueryChanged = viewModel::onDestinationQueryChanged,
                            onSelectDestination = viewModel::selectDestination,
                            onContinue = viewModel::continueFromStops,
                            onRetryStopSearch = viewModel::retryStopSearch,
                            onRetryDirections = viewModel::retryDirections,
                            onOpenPremium = onOpenPremium,
                        )
                        RoutineCreateStep.TRANSPORT_MODE -> TransportModeStep(
                            uiState = uiState,
                            onSelectMode = viewModel::selectTransportMode,
                        )
                        RoutineCreateStep.DIRECTION -> DirectionStep(
                            uiState = uiState,
                            onSelectDirection = viewModel::selectDirection,
                        )
                        RoutineCreateStep.SCHEDULE -> {
                            // A newly saved routine is, by default, enabled and therefore
                            // intended to show automatic notifications once its scheduled
                            // window opens -- save is exactly the "appropriate user-driven
                            // point" the product doc asks for to request POST_NOTIFICATIONS,
                            // with a brief rationale first (see rememberNotificationPermissionGate).
                            val notifyGate = rememberNotificationPermissionGate(
                                hasSeenRationale = uiState.hasSeenNotificationRationale,
                                onRationaleSeen = viewModel::markNotificationRationaleSeen,
                            )
                            ScheduleStep(
                                uiState = uiState,
                                onToggleDay = viewModel::toggleDay,
                                onStartTimeChanged = viewModel::setStartTime,
                                onEndTimeChanged = viewModel::setEndTime,
                                onNameChanged = viewModel::setName,
                                onSave = { notifyGate { viewModel.save(onDone) } },
                                // Retries only the WorkManager side for the already-saved routine
                                // -- never re-wrapped in notifyGate, which is specifically about
                                // the one-time POST_NOTIFICATIONS rationale for a fresh save, not
                                // this secondary scheduling retry (see RoutineCreateViewModel.save's
                                // own doc).
                                onRetryScheduling = { viewModel.retryScheduling(onDone) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun BlockedMessage(text: String, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDone) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
private fun stepTitle(step: RoutineCreateStep): String = stringResource(
    when (step) {
        RoutineCreateStep.STOP -> R.string.routine_create_step_stop
        RoutineCreateStep.TRANSPORT_MODE -> R.string.routine_create_step_mode
        RoutineCreateStep.DIRECTION -> R.string.routine_create_step_direction
        RoutineCreateStep.SCHEDULE -> R.string.routine_create_step_schedule
    },
)

@Composable
internal fun OriginDestinationStep(
    uiState: RoutineCreateUiState,
    onQueryChanged: (String) -> Unit,
    onSelectSite: (Site) -> Unit,
    onDestinationQueryChanged: (String) -> Unit,
    onSelectDestination: (JourneyLocation) -> Unit,
    onContinue: () -> Unit,
    onRetryStopSearch: () -> Unit,
    onRetryDirections: () -> Unit,
    // Defaulted -- only RoutineCreateScreen's own real call site (via BlickNavHost) passes an
    // actual navigation callback; every other existing call site (RoutineCreateScreenTest's
    // setUnifiedOriginDestinationContent) has no premium screen to navigate to and doesn't
    // care about this control.
    onOpenPremium: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = uiState.siteQuery,
            onValueChange = onQueryChanged,
            label = { Text(stringResource(R.string.routine_create_origin_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        when {
            !uiState.isExactDestination && uiState.isLoadingDirections -> CenteredMessage {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.routine_create_checking_departures,
                        uiState.selectedSite?.name.orEmpty(),
                    ),
                )
            }
            // A real failure (network/server/deserialization) loading directions for the
            // selected site — distinct from directionsEmpty below, which is a successful
            // lookup that legitimately found nothing running right now.
            !uiState.isExactDestination && uiState.directionsFailed -> Column {
                Text(
                    stringResource(
                        R.string.routine_create_directions_failed_error,
                        uiState.selectedSite?.name.orEmpty(),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetryDirections) { Text(stringResource(R.string.routine_create_retry)) }
            }
            !uiState.isExactDestination && uiState.directionsEmpty -> Column {
                Text(
                    stringResource(
                        R.string.routine_create_no_departures_error,
                        uiState.selectedSite?.name.orEmpty(),
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetryDirections) { Text(stringResource(R.string.routine_create_retry)) }
            }
            uiState.isSearching -> CenteredMessage { CircularProgressIndicator() }
            // Fixed, friendly copy only — never the raw exception/hostname/class name (see
            // RoutineCreateUiState's class doc). Has its own "Try again" wired to
            // retryStopSearch(), never to the unrelated retryDirections().
            uiState.searchFailed -> Column {
                Text(
                    stringResource(R.string.routine_create_search_error),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetryStopSearch) { Text(stringResource(R.string.routine_create_retry)) }
            }
            uiState.selectedSite == null && uiState.siteResults.isEmpty() && uiState.siteQuery.isNotBlank() ->
                Text(stringResource(R.string.routine_create_no_results))
            uiState.selectedSite == null -> Column {
                uiState.siteResults.take(5).forEach { site ->
                    ListItem(
                        headlineContent = { Text(site.name) },
                        supportingContent = site.note?.let { note -> { Text(note) } },
                        modifier = Modifier.clickable { onSelectSite(site) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.destinationQuery,
            onValueChange = onDestinationQueryChanged,
            enabled = uiState.hasPremium,
            label = { Text(stringResource(R.string.routine_create_destination_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (uiState.hasPremium) R.string.routine_create_destination_optional_hint
                        else R.string.routine_create_destination_premium_hint,
                    ),
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("destination-field"),
        )
        when {
            uiState.isSearchingDestination -> CenteredMessage { CircularProgressIndicator() }
            uiState.destinationSearchFailed -> Text(
                stringResource(R.string.routine_create_destination_search_error),
                color = MaterialTheme.colorScheme.error,
            )
            uiState.selectedDestination == null && uiState.destinationQuery.isNotBlank() &&
                uiState.destinationResults.isEmpty() -> Text(stringResource(R.string.routine_create_no_results))
            uiState.selectedDestination == null -> Column {
                uiState.destinationResults.take(5).forEach { destination ->
                    ListItem(
                        headlineContent = { Text(destination.name) },
                        modifier = Modifier.clickable { onSelectDestination(destination) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            uiState.isExactDestination && uiState.isResolvingJourneyOrigin ->
                Text(stringResource(R.string.routine_create_resolving_origin))
            uiState.isExactDestination && uiState.journeyOriginResolutionFailed ->
                Text(stringResource(R.string.routine_create_origin_resolution_error), color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onContinue, enabled = uiState.canContinueFromStops, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.routine_create_continue))
        }

        // Free-only, and placed below the primary Continue action rather than beside the
        // destination field above -- a supplementary offer, not a second competing call to
        // action at the point where a free user is already trying to move forward with a
        // line/direction routine. Still reachable with no more than a short scroll: this
        // whole step is already a single scrollable Column (see the Modifier above).
        if (!uiState.hasPremium) {
            Spacer(Modifier.height(24.dp))
            PremiumUpsellCard(onOpenPremium = onOpenPremium)
        }
    }
}

/** The transparent, always-reachable path from "I can't set an exact destination" (the
 * disabled destination field and its own hint above) to actually getting Premium — see
 * [OriginDestinationStep]'s own call site. Previously the only way to reach the Premium
 * screen from this wizard was to already know the routine list screen's free-routine-limit
 * dialog existed. */
@Composable
private fun PremiumUpsellCard(onOpenPremium: () -> Unit) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.routine_create_premium_upsell_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenPremium, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.routine_create_premium_upsell_button))
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun TransportModeStep(
    uiState: RoutineCreateUiState,
    onSelectMode: (TransportMode) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        uiState.availableTransportModes.forEach { mode ->
            ListItem(
                headlineContent = { Text(stringResource(mode.labelResId())) },
                modifier = Modifier.clickable { onSelectMode(mode) },
            )
        }
    }
}

/** `internal`, not `private` — exercised directly by `RoutineCreateScreenTest`, the same
 * direct-composable-test convention [WeekdaySelector]/`RoutineDetailsContent` already use. */
@Composable
internal fun DirectionStep(
    uiState: RoutineCreateUiState,
    onSelectDirection: (DirectionOption) -> Unit,
) {
    val options = uiState.directionOptions.filter { it.transportMode == uiState.selectedTransportMode }
    LazyColumn(Modifier.fillMaxSize()) {
        items(options, key = { "${it.lineId}-${it.directionCode}" }) { option ->
            val destination = option.destinationLabel ?: stringResource(R.string.direction_unknown_destination)
            ListItem(
                leadingContent = { LineBadge(lineDesignation = option.lineDesignation, transportMode = option.transportMode) },
                headlineContent = { Text(destination) },
                modifier = Modifier.clickable { onSelectDirection(option) },
            )
        }
    }
}

private enum class TimeField { START, END }

@Composable
private fun ScheduleStep(
    uiState: RoutineCreateUiState,
    onToggleDay: (DayOfWeek) -> Unit,
    onStartTimeChanged: (LocalTime) -> Unit,
    onEndTimeChanged: (LocalTime) -> Unit,
    onNameChanged: (String) -> Unit,
    onSave: () -> Unit,
    onRetryScheduling: () -> Unit,
) {
    var editingField by remember { mutableStateOf<TimeField?>(null) }
    // currentBlickLocale() reads Compose-observable CompositionLocal state internally (not
    // Locale.getDefault(), which does not recompose when the user changes the system locale --
    // see lint id "NonObservableLocale") and normalizes it to Blick's effective English/Svenska
    // presentation locale -- see that function's own doc.
    val locale = currentBlickLocale()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.routine_create_days_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        WeekdaySelector(activeDays = uiState.activeDays, onToggleDay = onToggleDay, locale = locale)
        if (!uiState.hasSelectedDays) {
            Text(
                stringResource(R.string.routine_create_error_no_days),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))
        TimePickerRow(
            label = stringResource(R.string.routine_create_start_time_label),
            time = uiState.startTime,
            onClick = { editingField = TimeField.START },
        )
        TimePickerRow(
            label = stringResource(R.string.routine_create_end_time_label),
            time = uiState.endTime,
            onClick = { editingField = TimeField.END },
        )
        if (!uiState.isTimeRangeValid) {
            Text(
                stringResource(R.string.routine_create_error_time_range),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (uiState.durationLimitExceeded) {
            Text(
                stringResource(R.string.routine_create_error_duration_limit),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (uiState.scheduleOverlap) {
            Text(
                stringResource(R.string.routine_create_error_overlap),
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChanged,
            label = { Text(stringResource(R.string.routine_create_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.saveFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.routine_create_save_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, enabled = uiState.canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (uiState.saveFailed) R.string.routine_create_retry else R.string.routine_create_save))
        }

        // The routine itself is already saved at this point (schedulingFailed and saveFailed
        // are mutually exclusive in practice) -- a dedicated retry, separate from the Save
        // button above, which targets only the WorkManager side and never repeats the write.
        if (uiState.schedulingFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.routine_create_scheduling_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetryScheduling,
                enabled = !uiState.isRetryingScheduling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }

    editingField?.let { field ->
        val initial = if (field == TimeField.START) uiState.startTime else uiState.endTime
        TimePickerDialogHost(
            initial = initial,
            onConfirm = { time ->
                if (field == TimeField.START) onStartTimeChanged(time) else onEndTimeChanged(time)
                editingField = null
            },
            onDismiss = { editingField = null },
        )
    }
}

/** Below this available width, all seven day chips in a single row would either overflow
 * (forcing horizontal scrolling) or squeeze under the 48dp minimum touch-target size on a
 * typical phone — see [WeekdaySelector]'s own doc. Comfortably above what a single row of
 * seven short day labels needs on any tablet or landscape width actually seen in testing. */
internal val WEEKDAY_SINGLE_ROW_MIN_WIDTH = 400.dp

/**
 * All seven [DayOfWeek] values as equally-weighted [FilterChip]s, laid out responsively so
 * every day is always visible and selectable with no horizontal scrolling, on both phones and
 * tablets: a single row spanning the full width when [BoxWithConstraints]' measured
 * `maxWidth` is at least [WEEKDAY_SINGLE_ROW_MIN_WIDTH] (comfortably true for tablets and
 * landscape phones), otherwise two balanced rows (Monday–Thursday, then Friday–Sunday) so no
 * day ever wraps off-screen or requires scrolling on a narrow phone in portrait.
 *
 * Each [WeekdayRow] gives every chip in it an equal [Modifier.weight] share of the row's
 * width — the same mechanism that makes both the seven-chip and the four/three-chip split
 * "balanced" (every chip in a row is the same width) and that keeps each day's short label on
 * one line (`maxLines = 1`) even at the narrowest supported width, since dividing by 4 (the
 * narrower of the two split rows) still leaves comfortably more than the 48dp minimum
 * interactive size Material's own [FilterChip] already enforces.
 */
@Composable
internal fun WeekdaySelector(
    activeDays: Set<DayOfWeek>,
    onToggleDay: (DayOfWeek) -> Unit,
    locale: java.util.Locale,
) {
    val days = DayOfWeek.values().toList()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WEEKDAY_SINGLE_ROW_MIN_WIDTH) {
            WeekdayRow(days, activeDays, onToggleDay, locale)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WeekdayRow(days.subList(0, 4), activeDays, onToggleDay, locale)
                WeekdayRow(days.subList(4, 7), activeDays, onToggleDay, locale)
            }
        }
    }
}

@Composable
private fun WeekdayRow(
    days: List<DayOfWeek>,
    activeDays: Set<DayOfWeek>,
    onToggleDay: (DayOfWeek) -> Unit,
    locale: java.util.Locale,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { day ->
            FilterChip(
                selected = day in activeDays,
                onClick = { onToggleDay(day) },
                label = {
                    Text(
                        day.getDisplayName(TextStyle.SHORT, locale),
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimePickerRow(label: String, time: LocalTime, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(time.format(DateTimeFormatter.ofPattern("HH:mm")))
    }
}

@Composable
private fun TimePickerDialogHost(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}

private fun TransportMode.labelResId(): Int = when (this) {
    TransportMode.BUS -> R.string.transport_mode_bus
    TransportMode.METRO -> R.string.transport_mode_metro
    TransportMode.TRAIN -> R.string.transport_mode_train
    TransportMode.TRAM -> R.string.transport_mode_tram
    TransportMode.SHIP -> R.string.transport_mode_ship
    TransportMode.FERRY -> R.string.transport_mode_ferry
    TransportMode.TAXI -> R.string.transport_mode_taxi
    TransportMode.UNKNOWN -> R.string.transport_mode_unknown
}
