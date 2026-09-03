package se.blick.app.ui.screens.onetimeevent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.RoutineLabel
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.components.ScheduleSectionCard
import se.blick.app.ui.components.ScheduleValueControl
import se.blick.app.ui.components.scheduleFormOutlinedTextFieldColors
import se.blick.app.ui.components.visuals
import se.blick.app.ui.theme.CalmBlue40
import se.blick.app.ui.theme.CalmBlue80
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.Neutral40
import se.blick.app.ui.theme.Neutral80
import se.blick.app.ui.theme.StockholmNightSurfaces
import se.blick.app.ui.theme.themedScreenContainerColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun OneTimeEventEditorScreen(
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    onOpenPremium: () -> Unit,
    viewModel: OneTimeEventEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedEventId) { state.savedEventId?.let(onDone) }
    OneTimeEventEditorContent(
        state = state,
        onBack = onBack,
        onOpenPremium = onOpenPremium,
        onLabel = viewModel::setLabel,
        onName = viewModel::setName,
        onOriginQuery = viewModel::setOriginQuery,
        onDestinationQuery = viewModel::setDestinationQuery,
        onOrigin = viewModel::selectOrigin,
        onDestination = viewModel::selectDestination,
        onDate = viewModel::setDate,
        onTimeType = viewModel::setTimeType,
        onTime = viewModel::setTime,
        onSave = viewModel::save,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OneTimeEventEditorContent(
    state: OneTimeEventEditorUiState,
    onBack: () -> Unit,
    onOpenPremium: () -> Unit,
    onLabel: (OneTimeEventLabel) -> Unit,
    onName: (String) -> Unit,
    onOriginQuery: (String) -> Unit,
    onDestinationQuery: (String) -> Unit,
    onOrigin: (JourneyLocation) -> Unit,
    onDestination: (JourneyLocation) -> Unit,
    onDate: (LocalDate) -> Unit,
    onTimeType: (OneTimeEventTimeType) -> Unit,
    onTime: (LocalTime) -> Unit,
    onSave: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var activeLocationField by remember { mutableStateOf<EventLocationField?>(null) }
    val originSuggestionsExpanded = activeLocationField == EventLocationField.Origin && state.originResults.isNotEmpty()
    val destinationSuggestionsExpanded = activeLocationField == EventLocationField.Destination && state.destinationResults.isNotEmpty()
    val locale = currentBlickLocale()
    val focusManager = LocalFocusManager.current
    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = {
            BlickTopBar(
                title = stringResource(
                    if (state.isEditing) R.string.one_time_event_edit_title else R.string.one_time_event_new_title,
                ),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        } else {
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("one-time-event-content"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ScheduleSectionCard(
                            icon = { Icon(painterResource(R.drawable.ic_label_other), contentDescription = null) },
                            title = stringResource(R.string.one_time_event_label_heading),
                            modifier = Modifier.testTag("one-time-event-label-card"),
                        ) {
                            OneTimeEventLabelSelector(
                                selectedLabel = state.label,
                                onLabelSelected = onLabel,
                            )
                        }
                    }
                    item {
                        val nameDescription = stringResource(R.string.one_time_event_name)
                        ScheduleSectionCard(
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            title = nameDescription,
                            modifier = Modifier.testTag("one-time-event-name-card"),
                        ) {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = onName,
                                placeholder = { Text(stringResource(R.string.one_time_event_name_hint)) },
                                singleLine = true,
                                colors = scheduleFormOutlinedTextFieldColors(useLightSurface = true),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = nameDescription }
                                    .testTag("one-time-event-name-field"),
                            )
                        }
                    }
                    item {
                        ScheduleSectionCard(
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            title = stringResource(R.string.one_time_event_route_heading),
                            modifier = Modifier.testTag("one-time-event-route-card"),
                        ) {
                            LocationField(
                                value = state.originQuery,
                                label = stringResource(R.string.one_time_event_from),
                                results = state.originResults,
                                searching = state.isSearchingOrigin,
                                onValueChange = onOriginQuery,
                                onSelect = onOrigin,
                                expanded = originSuggestionsExpanded,
                                onExpandedChange = { expanded ->
                                    if (expanded) activeLocationField = EventLocationField.Origin
                                    else if (activeLocationField == EventLocationField.Origin) activeLocationField = null
                                },
                                testTag = "one-time-event-origin",
                            )
                            LocationField(
                                value = state.destinationQuery,
                                label = stringResource(R.string.one_time_event_to),
                                results = state.destinationResults,
                                searching = state.isSearchingDestination,
                                onValueChange = onDestinationQuery,
                                onSelect = onDestination,
                                expanded = destinationSuggestionsExpanded,
                                onExpandedChange = { expanded ->
                                    if (expanded) activeLocationField = EventLocationField.Destination
                                    else if (activeLocationField == EventLocationField.Destination) activeLocationField = null
                                },
                                testTag = "one-time-event-destination",
                            )
                        }
                    }
                    item {
                        val dateLabel = stringResource(R.string.one_time_event_date)
                        val timeLabel = stringResource(R.string.one_time_event_time)
                        val dateValue = state.date.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
                        )
                        val timeValue = state.time.format(DateTimeFormatter.ofPattern("HH:mm"))
                        ScheduleSectionCard(
                            icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                            title = stringResource(R.string.one_time_event_schedule_heading),
                            modifier = Modifier.testTag("one-time-event-schedule-card"),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScheduleValueControl(
                                    visibleLabel = dateLabel,
                                    value = dateValue,
                                    accessibilityDescription = stringResource(
                                        R.string.routine_create_time_control_description,
                                        dateLabel,
                                        dateValue,
                                    ),
                                    onClick = { showDatePicker = true },
                                    modifier = Modifier.weight(1f).testTag("one-time-event-date-control"),
                                )
                                ScheduleValueControl(
                                    visibleLabel = timeLabel,
                                    value = timeValue,
                                    accessibilityDescription = stringResource(
                                        R.string.routine_create_time_control_description,
                                        timeLabel,
                                        timeValue,
                                    ),
                                    onClick = { showTimePicker = true },
                                    modifier = Modifier.weight(1f).testTag("one-time-event-time-control"),
                                )
                            }
                            OneTimeEventTimeTypeSelector(
                                selectedType = state.timeType,
                                onTimeTypeSelected = onTimeType,
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                stringResource(R.string.one_time_event_preview_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.one_time_event_preview_future),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.searchFailed) item {
                        Text(stringResource(R.string.one_time_event_search_error), color = MaterialTheme.colorScheme.error)
                    }
                    state.error?.let { error ->
                        item { Text(editorErrorText(error), color = MaterialTheme.colorScheme.error) }
                    }
                    item(key = "save-event") {
                        Button(
                            onClick = if (state.hasPremium) onSave else onOpenPremium,
                            enabled = if (state.hasPremium) state.canSave else true,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("save-event-button"),
                        ) {
                            if (state.isSaving) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            else Text(stringResource(if (state.hasPremium) R.string.one_time_event_save else R.string.premium_feature_badge))
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(androidx.compose.ui.res.stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        ) { DatePicker(pickerState) }
    }
    if (showTimePicker) {
        val pickerState = rememberTimePickerState(state.time.hour, state.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTime(LocalTime.of(pickerState.hour, pickerState.minute))
                    showTimePicker = false
                }) { Text(androidx.compose.ui.res.stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
            text = {
                TimePicker(
                    pickerState,
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
}

@Composable
internal fun OneTimeEventLabelSelector(
    selectedLabel: OneTimeEventLabel,
    onLabelSelected: (OneTimeEventLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OneTimeEventLabel.entries.forEach { label ->
            OneTimeEventLabelChip(
                label = label,
                selected = selectedLabel == label,
                onClick = { onLabelSelected(label) },
                modifier = Modifier.testTag("one-time-event-label-${label.name.lowercase()}"),
            )
        }
    }
}

internal data class OneTimeEventLabelVisuals(
    val accent: Color,
    val unselectedContainer: Color,
)

/** Keeps one-time event labels in the same established color families as Routine labels. */
internal fun OneTimeEventLabel.visuals(darkTheme: Boolean): OneTimeEventLabelVisuals {
    val routineVisuals = when (this) {
        OneTimeEventLabel.TRAVEL -> RoutineLabel.WORK
        OneTimeEventLabel.EVENT -> RoutineLabel.GYM
        OneTimeEventLabel.APPOINTMENT -> RoutineLabel.STUDY
        OneTimeEventLabel.OTHER -> RoutineLabel.OTHER
    }.visuals(darkTheme)
    return OneTimeEventLabelVisuals(
        accent = routineVisuals.accent,
        unselectedContainer = routineVisuals.container,
    )
}

internal data class OneTimeEventLabelChipPalette(
    val accent: Color,
    val unselectedContainer: Color,
    val selectedContainer: Color,
)

internal fun OneTimeEventLabel.chipPalette(
    darkTheme: Boolean,
    useStockholmNightSurface: Boolean,
): OneTimeEventLabelChipPalette {
    val visuals = visuals(darkTheme)
    return OneTimeEventLabelChipPalette(
        accent = visuals.accent,
        unselectedContainer = if (useStockholmNightSurface) {
            visuals.accent.copy(alpha = 0.08f).compositeOver(StockholmNightSurfaces.Control)
        } else {
            visuals.unselectedContainer
        },
        selectedContainer = when {
            useStockholmNightSurface ->
                visuals.accent.copy(alpha = 0.18f).compositeOver(StockholmNightSurfaces.SelectedControl)
            darkTheme -> visuals.accent.copy(alpha = 0.28f)
            else -> visuals.accent.copy(alpha = 0.18f)
        },
    )
}

@Composable
private fun OneTimeEventLabelChip(
    label: OneTimeEventLabel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = label.chipPalette(isDarkTheme, useStockholmNightSurface)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(labelText(label)) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = palette.unselectedContainer,
            labelColor = palette.accent,
            selectedContainerColor = palette.selectedContainer,
            selectedLabelColor = palette.accent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (useStockholmNightSurface) {
                StockholmNightSurfaces.Border
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            selectedBorderColor = palette.accent,
            selectedBorderWidth = 2.dp,
        ),
        modifier = modifier,
    )
}

@Composable
private fun OneTimeEventTimeTypeSelector(
    selectedType: OneTimeEventTimeType,
    onTimeTypeSelected: (OneTimeEventTimeType) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OneTimeEventTimeType.entries.forEach { type ->
            EventTimeTypeChoiceChip(
                selected = selectedType == type,
                onClick = { onTimeTypeSelected(type) },
                label = stringResource(
                    if (type == OneTimeEventTimeType.ARRIVE_BY) {
                        R.string.one_time_event_arrive_by
                    } else {
                        R.string.one_time_event_leave_at
                    },
                ),
                modifier = Modifier.weight(1f).testTag("one-time-event-time-type-${type.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun EventTimeTypeChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedBackground = when {
        useStockholmNightSurface -> StockholmNightSurfaces.SelectedControl
        isDarkTheme -> Neutral40
        else -> CalmBlue80
    }
    val selectedBorder = when {
        useStockholmNightSurface -> MaterialTheme.colorScheme.outline
        isDarkTheme -> Neutral80
        else -> CalmBlue40
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (useStockholmNightSurface) StockholmNightSurfaces.Control else MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = selectedBackground,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = if (useStockholmNightSurface) {
                StockholmNightSurfaces.Border
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            selectedBorderColor = selectedBorder,
            selectedBorderWidth = 2.dp,
        ),
        modifier = modifier,
    )
}

private enum class EventLocationField { Origin, Destination }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationField(
    value: String,
    label: String,
    results: List<JourneyLocation>,
    searching: Boolean,
    onValueChange: (String) -> Unit,
    onSelect: (JourneyLocation) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val focusManager = LocalFocusManager.current
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onExpandedChange(true)
                onValueChange(it)
            },
            label = { Text(label) },
            singleLine = true,
            colors = scheduleFormOutlinedTextFieldColors(useLightSurface = true),
            trailingIcon = if (searching) ({ CircularProgressIndicator(modifier = Modifier.height(20.dp)) }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onExpandedChange(false)
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onExpandedChange(it.isFocused) }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .testTag("$testTag-field"),
        )
        // The editable anchor preserves the IME; the menu bounds itself around the IME
        // and scrolls independently of the form, on an opaque theme surface.
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("$testTag-suggestions"),
        ) {
            results.forEach { location ->
                DropdownMenuItem(
                    text = { Text(location.name, maxLines = 2) },
                    onClick = {
                        onExpandedChange(false)
                        onSelect(location)
                    },
                    modifier = Modifier.testTag("$testTag-result-${location.id}"),
                )
            }
        }
    }
}

@Composable
private fun labelText(label: OneTimeEventLabel) = stringResource(
    when (label) {
        OneTimeEventLabel.TRAVEL -> R.string.one_time_event_label_travel
        OneTimeEventLabel.EVENT -> R.string.one_time_event_label_event
        OneTimeEventLabel.APPOINTMENT -> R.string.one_time_event_label_appointment
        OneTimeEventLabel.OTHER -> R.string.one_time_event_label_other
    },
)

@Composable
private fun editorErrorText(error: OneTimeEventEditorError) = stringResource(
    when (error) {
        OneTimeEventEditorError.REQUIRED -> R.string.one_time_event_validation_required
        OneTimeEventEditorError.PAST -> R.string.one_time_event_validation_past
        OneTimeEventEditorError.SAME_LOCATION -> R.string.one_time_event_validation_same_location
        OneTimeEventEditorError.PREMIUM_REQUIRED -> R.string.one_time_event_locked
        OneTimeEventEditorError.SAVE_FAILED -> R.string.one_time_event_save_error
    },
)
