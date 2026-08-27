@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinecreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.data.repository.DirectionOption
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.components.BlickWizardHeader
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.components.RoutineLabelSelector
import se.blick.app.ui.notification.rememberNotificationPermissionGate
import se.blick.app.ui.theme.CalmBlue40
import se.blick.app.ui.theme.CalmBlue80
import se.blick.app.ui.theme.Neutral40
import se.blick.app.ui.theme.Neutral80
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.StockholmNightSurfaces
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
                    title = if (uiState.step == RoutineCreateStep.SCHEDULE && uiState.isEditMode) {
                        stringResource(R.string.routine_edit_step_schedule)
                    } else {
                        stepTitle(uiState.step)
                    },
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
            else -> {
                if (uiState.step == RoutineCreateStep.SCHEDULE) {
                    // A newly saved routine is, by default, enabled and therefore intended to
                    // show automatic notifications once its scheduled window opens. This is the
                    // existing user-driven permission gate; the redesigned schedule screen only
                    // changes presentation around it.
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
                        onLabelChanged = viewModel::setLabel,
                        onSave = { notifyGate { viewModel.save(onDone) } },
                        onRetryScheduling = { viewModel.retryScheduling(onDone) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    )
                } else {
                    Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
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
                            RoutineCreateStep.SCHEDULE -> Unit
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
internal fun ScheduleStep(
    uiState: RoutineCreateUiState,
    onToggleDay: (DayOfWeek) -> Unit,
    onStartTimeChanged: (LocalTime) -> Unit,
    onEndTimeChanged: (LocalTime) -> Unit,
    onNameChanged: (String) -> Unit,
    onLabelChanged: (RoutineLabel?) -> Unit,
    onSave: () -> Unit,
    onRetryScheduling: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingField by remember { mutableStateOf<TimeField?>(null) }
    var hasInteractedWithDays by rememberSaveable { mutableStateOf(false) }
    // currentBlickLocale() reads Compose-observable CompositionLocal state internally (not
    // Locale.getDefault(), which does not recompose when the user changes the system locale --
    // see lint id "NonObservableLocale") and normalizes it to Blick's effective English/Svenska
    // presentation locale -- see that function's own doc.
    val locale = currentBlickLocale()
    val focusManager = LocalFocusManager.current

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().testTag("schedule-content"),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScheduleSectionCard(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    title = stringResource(R.string.routine_create_days_label),
                    modifier = Modifier.testTag("active-days-card"),
                ) {
                    WeekdaySelector(
                        activeDays = uiState.activeDays,
                        onToggleDay = { day ->
                            hasInteractedWithDays = true
                            onToggleDay(day)
                        },
                        locale = locale,
                    )
                    if (hasInteractedWithDays && !uiState.hasSelectedDays) {
                        Text(
                            stringResource(R.string.routine_create_error_no_days),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("active-days-error"),
                        )
                    }
                }
            }

            item {
                ScheduleSectionCard(
                    icon = { Icon(painterResource(R.drawable.ic_clock), contentDescription = null) },
                    title = stringResource(R.string.routine_create_time_window_title),
                    modifier = Modifier.testTag("time-window-card"),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeControl(
                            visibleLabel = stringResource(R.string.routine_create_start_label),
                            accessibilityLabel = stringResource(R.string.routine_create_start_time_label),
                            time = uiState.startTime,
                            onClick = { editingField = TimeField.START },
                            modifier = Modifier.weight(1f).testTag("start-time-control"),
                        )
                        TimeControl(
                            visibleLabel = stringResource(R.string.routine_create_end_label),
                            accessibilityLabel = stringResource(R.string.routine_create_end_time_label),
                            time = uiState.endTime,
                            onClick = { editingField = TimeField.END },
                            modifier = Modifier.weight(1f).testTag("end-time-control"),
                        )
                    }
                    if (!uiState.isTimeRangeValid) {
                        ValidationText(stringResource(R.string.routine_create_error_time_range))
                    }
                    if (uiState.durationLimitExceeded) {
                        ValidationText(stringResource(R.string.routine_create_error_duration_limit))
                    }
                    if (uiState.scheduleOverlap) {
                        ValidationText(stringResource(R.string.routine_create_error_overlap))
                    }
                }
            }

            item {
                val routineNameDescription = stringResource(R.string.routine_create_name_label)
                ScheduleSectionCard(
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    title = routineNameDescription,
                    modifier = Modifier.testTag("routine-name-card"),
                ) {
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = onNameChanged,
                        singleLine = true,
                        colors = if (LocalStockholmNightTheme.current) {
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = StockholmNightSurfaces.Control,
                                unfocusedContainerColor = StockholmNightSurfaces.Control,
                                disabledContainerColor = StockholmNightSurfaces.Control,
                                errorContainerColor = StockholmNightSurfaces.Control,
                                unfocusedBorderColor = StockholmNightSurfaces.Border,
                                disabledBorderColor = StockholmNightSurfaces.Border,
                            )
                        } else {
                            OutlinedTextFieldDefaults.colors()
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = routineNameDescription }
                            .testTag("routine-name-field"),
                    )
                }
            }

            item {
                ScheduleSectionCard(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_label_other),
                            contentDescription = null,
                        )
                    },
                    title = stringResource(R.string.routine_label_field_title),
                    modifier = Modifier.testTag("routine-label-card"),
                ) {
                    RoutineLabelSelector(
                        selectedLabel = uiState.selectedLabel,
                        onLabelSelected = onLabelChanged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (uiState.saveFailed) {
                item { ValidationText(stringResource(R.string.routine_create_save_error)) }
            }

            // The routine itself is already saved at this point. This existing retry targets
            // only scheduling and never repeats the Room write.
            if (uiState.schedulingFailed) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValidationText(stringResource(R.string.routine_create_scheduling_error))
                        Button(
                            onClick = onRetryScheduling,
                            enabled = !uiState.isRetryingScheduling,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .testTag("schedule-sticky-action"),
        ) {
            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("save-routine-button"),
            ) {
                Text(stringResource(if (uiState.saveFailed) R.string.routine_create_retry else R.string.routine_create_save))
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
internal val WEEKDAY_SINGLE_ROW_MIN_WIDTH = 348.dp

/**
 * All seven [DayOfWeek] values as equal 48dp-high selectors, laid out responsively so
 * every day is always visible and selectable with no horizontal scrolling, on both phones and
 * tablets: a single row spanning the full width when [BoxWithConstraints]' measured
 * `maxWidth` is at least [WEEKDAY_SINGLE_ROW_MIN_WIDTH] (comfortably true for tablets and
 * landscape phones), otherwise two balanced rows (Monday–Thursday, then Friday–Sunday) so no
 * day ever wraps off-screen or requires scrolling on a narrow phone in portrait.
 *
 * Each [WeekdayRow] gives every selector an equal [Modifier.weight] share of the row's
 * width — the same mechanism that makes both the seven-chip and the four/three-chip split
 * "balanced" (every chip in a row is the same width) and that keeps each day's short label on
 * one line (`maxLines = 1`) even at the narrowest supported width, since dividing by 4 (the
 * narrower of the two split rows) still leaves comfortably more than the 48dp minimum
 * interactive height. The three-day second row keeps an empty fourth slot so its controls
 * remain exactly the same width as the four-day first row.
 */
@Composable
internal fun WeekdaySelector(
    activeDays: Set<DayOfWeek>,
    onToggleDay: (DayOfWeek) -> Unit,
    locale: java.util.Locale,
    modifier: Modifier = Modifier,
) {
    val days = DayOfWeek.values().toList()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= WEEKDAY_SINGLE_ROW_MIN_WIDTH) {
            WeekdayRow(days, 7, activeDays, onToggleDay, locale)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WeekdayRow(days.subList(0, 4), 4, activeDays, onToggleDay, locale)
                WeekdayRow(days.subList(4, 7), 4, activeDays, onToggleDay, locale)
            }
        }
    }
}

@Composable
private fun WeekdayRow(
    days: List<DayOfWeek>,
    totalSlots: Int,
    activeDays: Set<DayOfWeek>,
    onToggleDay: (DayOfWeek) -> Unit,
    locale: java.util.Locale,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val selectedBackground = when {
        useStockholmNightSurface -> StockholmNightSurfaces.SelectedControl
        isDarkTheme -> Neutral40
        else -> CalmBlue80
    }
    val unselectedBackground = if (useStockholmNightSurface) {
        StockholmNightSurfaces.Control
    } else {
        MaterialTheme.colorScheme.surface
    }
    val selectedContent = MaterialTheme.colorScheme.onSurface
    val selectedOutline = when {
        useStockholmNightSurface -> MaterialTheme.colorScheme.outline
        isDarkTheme -> Neutral80
        else -> CalmBlue40
    }
    val unselectedOutline = if (useStockholmNightSurface) {
        StockholmNightSurfaces.Border
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        days.forEach { day ->
            val selected = day in activeDays
            Surface(
                color = if (selected) selectedBackground else unselectedBackground,
                contentColor = if (selected) selectedContent else MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) selectedOutline else unselectedOutline,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onToggleDay(day) },
                        role = Role.Checkbox,
                    )
                    .testTag("weekday-${day.name.lowercase()}"),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        day.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        repeat(totalSlots - days.size) {
            Spacer(Modifier.weight(1f).height(48.dp))
        }
    }
}

@Composable
private fun ScheduleSectionCard(
    icon: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable
private fun TimeControl(
    visibleLabel: String,
    accessibilityLabel: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedTime = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val description = stringResource(R.string.routine_create_time_control_description, accessibilityLabel, formattedTime)
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (useStockholmNightSurface) StockholmNightSurfaces.Control else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (useStockholmNightSurface) StockholmNightSurfaces.Border else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .height(80.dp)
            .semantics { contentDescription = description },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                visibleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formattedTime, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ValidationText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    selectorColor = MaterialTheme.colorScheme.primary,
                    clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primary,
                    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
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
