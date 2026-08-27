package se.blick.app.ui.screens.onetimeevent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.StockholmNightSurfaces
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
fun OneTimeEventsScreen(
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    viewModel: OneTimeEventsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OneTimeEventsContent(state, onBack, onOpenEvent)
}

@Composable
internal fun OneTimeEventsContent(
    state: OneTimeEventsUiState,
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    val today = LocalDate.now(STOCKHOLM_ZONE)
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val eventsByDate = state.events.groupBy(OneTimeEvent::date)
    val visibleEvents = selectedDate?.let { eventsByDate[it].orEmpty() } ?: state.events
    val locale = currentBlickLocale()
    Scaffold(topBar = { BlickTopBar(stringResource(R.string.one_time_event_calendar_title), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { month = month.minusMonths(1); selectedDate = null }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        month.atDay(1).month.getDisplayName(TextStyle.FULL, locale)
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } + " ${month.year}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = { month = month.plusMonths(1); selectedDate = null }) {
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            item {
                CalendarMonth(
                    month = month,
                    selectedDate = selectedDate,
                    eventDates = eventsByDate.keys,
                    onSelect = { selectedDate = if (selectedDate == it) null else it },
                )
            }
            if (state.isLoading) item { CircularProgressIndicator() }
            else if (visibleEvents.isEmpty()) item {
                Text(
                    stringResource(R.string.one_time_event_no_upcoming),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(visibleEvents, key = OneTimeEvent::id) { event ->
                OneTimeEventCard(event = event, onClick = { onOpenEvent(event.id) })
            }
        }
    }
}

@Composable
private fun CalendarMonth(
    month: YearMonth,
    selectedDate: LocalDate?,
    eventDates: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    val cells = buildList<LocalDate?> {
        repeat(month.atDay(1).dayOfWeek.value - 1) { add(null) }
        (1..month.lengthOfMonth()).forEach { add(month.atDay(it)) }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxWidth().aspectRatio(1.45f),
    ) {
        gridItems(cells) { date ->
            Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (date != null) {
                    Surface(
                        onClick = { onSelect(date) },
                        shape = MaterialTheme.shapes.small,
                        color = if (selectedDate == date) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString())
                            if (date in eventDates) {
                                Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OneTimeEventCard(
    event: OneTimeEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    matchHomeRoutineCardEdges: Boolean = false,
) {
    val locale = currentBlickLocale()
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = if (matchHomeRoutineCardEdges) RoundedCornerShape(16.dp) else MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (matchHomeRoutineCardEdges && useStockholmNightSurface) {
                StockholmNightSurfaces.Card
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (matchHomeRoutineCardEdges) {
            if (useStockholmNightSurface) BorderStroke(1.dp, StockholmNightSurfaces.CardBorder) else null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        elevation = if (matchHomeRoutineCardEdges) {
            CardDefaults.cardElevation(defaultElevation = 2.dp)
        } else {
            CardDefaults.cardElevation()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OneTimeEventLabelPill(event.label)
                Text(
                    event.date.format(DateTimeFormatter.ofPattern("d MMM", locale)).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (locked) {
                Text(
                    stringResource(R.string.one_time_event_locked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(event.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                stringResource(R.string.one_time_event_route_format, event.originName, event.destinationName),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (event.timeType == OneTimeEventTimeType.ARRIVE_BY) R.string.one_time_event_arrive_by_value
                    else R.string.one_time_event_leave_at_value,
                    event.time.toString(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OneTimeEventLabelPill(label: OneTimeEventLabel) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = eventLabelText(label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun OneTimeEventDetailsScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: OneTimeEventDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    Scaffold(topBar = { BlickTopBar(state.event?.name, onBack) }) { padding ->
        val event = state.event
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (event != null) {
            val locale = currentBlickLocale()
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(eventLabelText(event.label), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(
                    event.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.one_time_event_route_format, event.originName, event.destinationName))
                Text(
                    stringResource(
                        if (event.timeType == OneTimeEventTimeType.ARRIVE_BY) R.string.one_time_event_arrive_by_value
                        else R.string.one_time_event_leave_at_value,
                        event.time.toString(),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.one_time_event_preview_title), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.one_time_event_preview_future),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = { onEdit(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.one_time_event_edit_action))
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.one_time_event_delete_action), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.one_time_event_delete_title)) },
            text = { Text(stringResource(R.string.one_time_event_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.one_time_event_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.one_time_event_delete_cancel))
                }
            },
        )
    }
}

@Composable
internal fun eventLabelText(label: OneTimeEventLabel) = stringResource(
    when (label) {
        OneTimeEventLabel.TRAVEL -> R.string.one_time_event_label_travel
        OneTimeEventLabel.EVENT -> R.string.one_time_event_label_event
        OneTimeEventLabel.APPOINTMENT -> R.string.one_time_event_label_appointment
        OneTimeEventLabel.OTHER -> R.string.one_time_event_label_other
    },
)
