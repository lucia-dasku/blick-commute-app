@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinedetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.model.TransportMode

/**
 * Routine details / live-preview screen: loads one saved routine and shows its next two
 * relevant departures via [RoutineDetailsViewModel] + the existing live-departure engine.
 * Foreground, manually refreshable only — no periodic refresh, no notifications, no
 * editing (see this milestone's scope).
 */
@Composable
fun RoutineDetailsScreen(
    onBack: () -> Unit,
    viewModel: RoutineDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routine_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val routine = uiState.routine
        when {
            uiState.isRoutineLoading -> CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator()
            }
            // routine == null after loading has finished is exactly what routineNotFound
            // means (see RoutineDetailsViewModel.init); checking null directly here, rather
            // than uiState.routineNotFound, keeps this a plain nullability check so `routine`
            // reliably smart-casts to non-null in the branch below.
            routine == null -> CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                Text(stringResource(R.string.routine_details_not_found))
            }
            else -> RoutineDetailsContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                routine = routine,
                isPausedToday = uiState.isPausedToday,
                departuresState = uiState.departures,
                isRefreshing = uiState.isRefreshingDepartures,
                onRefresh = viewModel::refresh,
            )
        }
    }
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun RoutineDetailsContent(
    modifier: Modifier,
    routine: CommuteRoutine,
    isPausedToday: Boolean,
    departuresState: LiveDeparturesState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(routine.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(routine.siteName, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(12.dp))
        DetailRow(stringResource(R.string.routine_details_mode_label), stringResource(routine.transportMode.detailsLabelResId()))
        routine.lineDesignation?.let { designation ->
            DetailRow(stringResource(R.string.routine_details_line_label), designation)
        }
        routine.destinationLabel?.let { destination ->
            DetailRow(stringResource(R.string.routine_details_direction_label), destination)
        }
        DetailRow(stringResource(R.string.routine_create_days_label), formatActiveDays(routine.activeDays, locale))
        DetailRow(stringResource(R.string.routine_details_time_label), formatTimeRange(routine.startTime, routine.endTime, locale))
        DetailRow(stringResource(R.string.routine_details_status_label), statusLabel(routine, isPausedToday))

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.routine_details_departures_heading), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRefresh, enabled = !isRefreshing) {
                Text(stringResource(R.string.routine_details_refresh_action))
            }
        }
        if (isRefreshing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))

        DeparturesSection(departuresState, locale, onRefresh)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun statusLabel(routine: CommuteRoutine, isPausedToday: Boolean): String = when {
    isPausedToday -> stringResource(R.string.routine_details_status_paused_today)
    routine.enabled -> stringResource(R.string.routine_details_status_enabled)
    else -> stringResource(R.string.routine_details_status_disabled)
}

@Composable
private fun DeparturesSection(
    state: LiveDeparturesState,
    locale: java.util.Locale,
    onRefresh: () -> Unit,
) {
    when (state) {
        is LiveDeparturesState.Loading -> CenteredBox(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            CircularProgressIndicator()
        }
        is LiveDeparturesState.Live -> DeparturesList(state.snapshot.departures, locale)
        is LiveDeparturesState.Stale -> Column {
            Text(
                stringResource(R.string.routine_details_stale_warning),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            DeparturesList(state.snapshot.departures, locale)
        }
        is LiveDeparturesState.NoUpcomingDepartures -> RetryableMessage(R.string.routine_details_no_departures, onRefresh)
        is LiveDeparturesState.Offline -> RetryableMessage(R.string.routine_details_offline, onRefresh)
        is LiveDeparturesState.Unavailable -> RetryableMessage(R.string.routine_details_unavailable, onRefresh)
    }
}

@Composable
private fun RetryableMessage(messageRes: Int, onRefresh: () -> Unit) {
    Column {
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRefresh) { Text(stringResource(R.string.routine_details_refresh_action)) }
    }
}

@Composable
private fun DeparturesList(departures: List<PreparedDeparture>, locale: java.util.Locale) {
    Column {
        departures.forEach { departure ->
            DepartureRow(departure, locale)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DepartureRow(departure: PreparedDeparture, locale: java.util.Locale) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${departure.lineDesignation}  →  ${departure.destination ?: stringResource(R.string.direction_unknown_destination)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.routine_details_minutes_remaining, departure.minutesRemaining),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatDepartureTime(departure.effectiveTime, locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                departureStatusLabel(departure),
                style = MaterialTheme.typography.bodySmall,
                color = if (departure.isCancelled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun departureStatusLabel(departure: PreparedDeparture): String = when {
    // Cancellation is called out on its own — a departure can be both "real-time" and
    // cancelled, and cancellation is the more important fact to lead with.
    departure.isCancelled -> stringResource(R.string.routine_details_departure_cancelled)
    departure.isRealTime -> stringResource(R.string.routine_details_departure_live)
    else -> stringResource(R.string.routine_details_departure_scheduled)
}

private fun TransportMode.detailsLabelResId(): Int = when (this) {
    TransportMode.BUS -> R.string.transport_mode_bus
    TransportMode.METRO -> R.string.transport_mode_metro
    TransportMode.TRAIN -> R.string.transport_mode_train
    TransportMode.TRAM -> R.string.transport_mode_tram
    TransportMode.SHIP -> R.string.transport_mode_ship
    TransportMode.FERRY -> R.string.transport_mode_ferry
    TransportMode.TAXI -> R.string.transport_mode_taxi
    TransportMode.UNKNOWN -> R.string.transport_mode_unknown
}
