@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinecreate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.data.repository.DirectionOption
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.TransportMode
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
    viewModel: RoutineCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stepTitle(uiState.step)) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            LinearProgressIndicator(
                progress = { (uiState.step.ordinal + 1) / 4f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (uiState.step) {
                    RoutineCreateStep.STOP -> StopStep(
                        uiState = uiState,
                        onQueryChanged = viewModel::onSiteQueryChanged,
                        onSelectSite = viewModel::selectSite,
                        onRetry = viewModel::retryDirections,
                    )
                    RoutineCreateStep.TRANSPORT_MODE -> TransportModeStep(
                        uiState = uiState,
                        onSelectMode = viewModel::selectTransportMode,
                    )
                    RoutineCreateStep.DIRECTION -> DirectionStep(
                        uiState = uiState,
                        onSelectDirection = viewModel::selectDirection,
                    )
                    RoutineCreateStep.SCHEDULE -> ScheduleStep(
                        uiState = uiState,
                        onToggleDay = viewModel::toggleDay,
                        onStartTimeChanged = viewModel::setStartTime,
                        onEndTimeChanged = viewModel::setEndTime,
                        onNameChanged = viewModel::setName,
                        onSave = { viewModel.save(onDone) },
                    )
                }
            }
        }
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
private fun StopStep(
    uiState: RoutineCreateUiState,
    onQueryChanged: (String) -> Unit,
    onSelectSite: (Site) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.siteQuery,
            onValueChange = onQueryChanged,
            label = { Text(stringResource(R.string.routine_create_stop_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        when {
            uiState.isLoadingDirections -> CenteredMessage {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.routine_create_checking_departures,
                        uiState.selectedSite?.name.orEmpty(),
                    ),
                )
            }
            uiState.directionsError -> Column {
                Text(
                    stringResource(
                        R.string.routine_create_no_departures_error,
                        uiState.selectedSite?.name.orEmpty(),
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.routine_create_retry)) }
            }
            uiState.isSearching -> CenteredMessage { CircularProgressIndicator() }
            uiState.searchErrorMessage != null -> Text(
                stringResource(R.string.routine_create_search_error, uiState.searchErrorMessage),
                color = MaterialTheme.colorScheme.error,
            )
            uiState.siteResults.isEmpty() && uiState.siteQuery.isNotBlank() ->
                Text(stringResource(R.string.routine_create_no_results))
            else -> LazyColumn {
                items(uiState.siteResults, key = { it.siteId }) { site ->
                    ListItem(
                        headlineContent = { Text(site.name) },
                        supportingContent = site.note?.let { note -> { Text(note) } },
                        modifier = Modifier.clickable { onSelectSite(site) },
                    )
                }
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

@Composable
private fun DirectionStep(
    uiState: RoutineCreateUiState,
    onSelectDirection: (DirectionOption) -> Unit,
) {
    val options = uiState.directionOptions.filter { it.transportMode == uiState.selectedTransportMode }
    LazyColumn(Modifier.fillMaxSize()) {
        items(options, key = { "${it.lineId}-${it.directionCode}" }) { option ->
            val destination = option.destinationLabel ?: stringResource(R.string.direction_unknown_destination)
            ListItem(
                headlineContent = { Text("${option.lineDesignation}  →  $destination") },
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
) {
    var editingField by remember { mutableStateOf<TimeField?>(null) }
    // Read via LocalLocale (a Compose-observable CompositionLocal) rather than
    // Locale.getDefault(), which does not recompose when the user changes the system
    // locale (see lint id "NonObservableLocale").
    val locale = LocalLocale.current.platformLocale

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.routine_create_days_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.values().forEach { day ->
                FilterChip(
                    selected = day in uiState.activeDays,
                    onClick = { onToggleDay(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                )
            }
        }
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

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChanged,
            label = { Text(stringResource(R.string.routine_create_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, enabled = uiState.canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.routine_create_save))
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
