package se.blick.app.ui.screens.onetimeevent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.domain.model.JourneyLocation
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
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
    val locale = currentBlickLocale()
    Scaffold(
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SectionTitle(stringResource(R.string.one_time_event_label_heading))
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneTimeEventLabel.entries.forEach { label ->
                            val selected = state.label == label
                            FilterChip(
                                selected = selected,
                                onClick = { onLabel(label) },
                                label = { Text(labelText(label)) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = onName,
                        label = { Text(stringResource(R.string.one_time_event_name)) },
                        placeholder = { Text(stringResource(R.string.one_time_event_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    LocationField(
                        value = state.originQuery,
                        label = stringResource(R.string.one_time_event_from),
                        results = state.originResults,
                        searching = state.isSearchingOrigin,
                        onValueChange = onOriginQuery,
                        onSelect = onOrigin,
                    )
                }
                item {
                    LocationField(
                        value = state.destinationQuery,
                        label = stringResource(R.string.one_time_event_to),
                        results = state.destinationResults,
                        searching = state.isSearchingDestination,
                        onValueChange = onDestinationQuery,
                        onSelect = onDestination,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                            Column {
                                Text(stringResource(R.string.one_time_event_date), style = MaterialTheme.typography.labelSmall)
                                Text(state.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
                            }
                        }
                        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                            Column {
                                Text(stringResource(R.string.one_time_event_time), style = MaterialTheme.typography.labelSmall)
                                Text(state.time.toString())
                            }
                        }
                    }
                }
                item {
                    SectionTitle(stringResource(R.string.one_time_event_time_type))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneTimeEventTimeType.entries.forEach { type ->
                            FilterChip(
                                selected = state.timeType == type,
                                onClick = { onTimeType(type) },
                                label = {
                                    Text(
                                        stringResource(
                                            if (type == OneTimeEventTimeType.ARRIVE_BY) {
                                                R.string.one_time_event_arrive_by
                                            } else R.string.one_time_event_leave_at,
                                        ),
                                    )
                                },
                                leadingIcon = if (state.timeType == type) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.one_time_event_preview_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.one_time_event_preview_future),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.searchFailed) item {
                    Text(stringResource(R.string.one_time_event_search_error), color = MaterialTheme.colorScheme.error)
                }
                state.error?.let { error ->
                    item { Text(editorErrorText(error), color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Button(
                        onClick = if (state.hasPremium) onSave else onOpenPremium,
                        enabled = if (state.hasPremium) state.canSave else true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        else Text(stringResource(if (state.hasPremium) R.string.one_time_event_save else R.string.premium_feature_badge))
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
private fun LocationField(
    value: String,
    label: String,
    results: List<JourneyLocation>,
    searching: Boolean,
    onValueChange: (String) -> Unit,
    onSelect: (JourneyLocation) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = if (searching) ({ CircularProgressIndicator(modifier = Modifier.height(20.dp)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        results.take(5).forEach { location ->
            ListItem(
                headlineContent = { Text(location.name, maxLines = 2) },
                modifier = Modifier.fillMaxWidth().clickable { onSelect(location) },
            )
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

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
