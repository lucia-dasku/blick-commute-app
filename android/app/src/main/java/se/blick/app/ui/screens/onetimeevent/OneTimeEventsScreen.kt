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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.LocalLightCityTheme
import se.blick.app.ui.theme.LightOneTimeEventCardSurface
import se.blick.app.ui.theme.RoutineDestructiveRed
import se.blick.app.ui.theme.StockholmNightSurfaces
import se.blick.app.ui.theme.themedScreenContainerColor
import se.blick.app.domain.model.OneTimeEvent
import se.blick.app.domain.model.OneTimeEventLabel
import se.blick.app.domain.model.OneTimeEventTimeType
import se.blick.app.domain.model.PlannedJourneyRole
import se.blick.app.domain.model.toPresentation
import se.blick.app.domain.model.STOCKHOLM_ZONE
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.screens.routinedetails.PlannedJourneyTimelineCard
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.format.DateTimeFormatterBuilder

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
    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = { BlickTopBar(stringResource(R.string.one_time_event_calendar_title), onBack) },
    ) { padding ->
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
internal fun OneTimeEventLabelPill(label: OneTimeEventLabel) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag("one-time-event-label-pill-${label.name.lowercase()}"),
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
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    OneTimeEventDetailsContent(
        state = state,
        onBack = onBack,
        onEdit = onEdit,
        onDelete = viewModel::delete,
        onRefresh = viewModel::refreshPreview,
    )
}

@Composable
internal fun OneTimeEventDetailsContent(
    state: OneTimeEventDetailsUiState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = { BlickTopBar(state.event?.name, onBack) },
    ) { padding ->
        val event = state.event
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (event != null) {
            val locale = currentBlickLocale()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OneTimeEventSummary(event, locale)
                }
                item {
                    PlannedJourneySection(
                        preview = state.preview,
                        locale = locale,
                        presentation = state.presentation,
                        isRefreshing = state.isRefreshing,
                        refreshFailed = state.refreshFailed,
                        disruptionState = state.disruptionState,
                        onRefresh = onRefresh,
                    )
                }
                item {
                    Button(onClick = { onEdit(event.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.one_time_event_edit_action))
                    }
                }
                item {
                    Button(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoutineDestructiveRed,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.one_time_event_delete_action))
                    }
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
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
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
private fun OneTimeEventSummary(event: OneTimeEvent, locale: java.util.Locale) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val useLightEventSurface = LocalLightCityTheme.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            useStockholmNightSurface -> StockholmNightSurfaces.Card
            useLightEventSurface -> LightOneTimeEventCardSurface
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (useStockholmNightSurface) BorderStroke(1.dp, StockholmNightSurfaces.CardBorder) else null,
        tonalElevation = if (useStockholmNightSurface) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().testTag("one-time-event-summary"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OneTimeEventLabelPill(event.label)
            Text(
                event.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.one_time_event_route_format, event.originName, event.destinationName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (event.timeType == OneTimeEventTimeType.ARRIVE_BY) R.string.one_time_event_arrive_by_value
                    else R.string.one_time_event_leave_at_value,
                    event.time.toString(),
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun PlannedJourneySection(
    preview: PlannedJourneyPreviewState,
    locale: java.util.Locale,
    presentation: EventPlanPresentation? = EventPlanPresentation.PRELIMINARY,
    isRefreshing: Boolean = false,
    refreshFailed: Boolean = false,
    disruptionState: EventPlanDisruptionState = EventPlanDisruptionState.NotRequested,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        if (presentation == EventPlanPresentation.TODAY) {
                            R.string.one_time_event_today_plan_title
                        } else {
                            R.string.one_time_event_preliminary_plan_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (presentation == EventPlanPresentation.TODAY && preview is PlannedJourneyPreviewState.Ready) {
                    Text(
                        stringResource(
                            R.string.one_time_event_plan_updated,
                            DateTimeFormatterBuilder().appendPattern("HH:mm").toFormatter(locale)
                                .withZone(STOCKHOLM_ZONE).format(preview.result.fetchedAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview is PlannedJourneyPreviewState.Ready) {
                Button(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.one_time_event_planned_refresh))
                    }
                }
            }
        }
        when (preview) {
            PlannedJourneyPreviewState.WaitingForEntitlement,
            PlannedJourneyPreviewState.Loading,
            -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is PlannedJourneyPreviewState.Ready -> {
                val choices = preview.result.choices.sortedBy { it.journey.departureTime }
                var expandedRole by remember(preview.result.fetchedAt, choices.map { it.journey.journeyId }) {
                    mutableStateOf<PlannedJourneyRole?>(PlannedJourneyRole.RECOMMENDED)
                }
                choices.forEach { choice ->
                    PlannedJourneyTimelineCard(
                        journey = choice.journey,
                        optionLabel = stringResource(
                            when (choice.role) {
                                PlannedJourneyRole.EARLIER -> R.string.one_time_event_plan_earlier_option
                                PlannedJourneyRole.RECOMMENDED -> R.string.one_time_event_plan_recommended
                                PlannedJourneyRole.LATER -> R.string.one_time_event_plan_later_option
                            },
                        ),
                        locale = locale,
                        emphasized = choice.role == PlannedJourneyRole.RECOMMENDED,
                        expanded = expandedRole == choice.role,
                        onExpandedChange = { expand ->
                            expandedRole = if (expand) choice.role else null
                        },
                        modifier = Modifier.testTag("planned-journey-card-${choice.role.name.lowercase()}"),
                    )
                }
                if (presentation == EventPlanPresentation.TODAY) {
                    EventPlanDisruptions(disruptionState)
                }
                if (refreshFailed) {
                    Text(
                        stringResource(R.string.one_time_event_planned_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PlannedJourneyInfoSurface(
                    text = stringResource(
                        if (presentation == EventPlanPresentation.TODAY) {
                            R.string.one_time_event_today_plan_explanation
                        } else {
                            R.string.one_time_event_preliminary_plan_disclaimer
                        },
                    ),
                )
            }
            PlannedJourneyPreviewState.NoJourney -> PlannedPreviewMessage(
                text = stringResource(R.string.one_time_event_planned_no_journey),
                action = onRefresh,
            )
            PlannedJourneyPreviewState.Error -> PlannedPreviewMessage(
                text = stringResource(R.string.one_time_event_planned_error),
                action = onRefresh,
            )
            PlannedJourneyPreviewState.Expired -> PlannedPreviewMessage(
                text = stringResource(R.string.one_time_event_planned_expired),
            )
            PlannedJourneyPreviewState.PremiumRequired -> PlannedPreviewMessage(
                text = stringResource(R.string.one_time_event_planned_premium_required),
            )
        }
    }
}

@Composable
private fun PlannedJourneyInfoSurface(text: String) {
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val useLightEventSurface = LocalLightCityTheme.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            useStockholmNightSurface -> StockholmNightSurfaces.Control
            useLightEventSurface -> LightOneTimeEventCardSurface
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (useStockholmNightSurface) BorderStroke(1.dp, StockholmNightSurfaces.CardBorder) else null,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("one-time-event-plan-info"),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventPlanDisruptions(state: EventPlanDisruptionState) {
    when (state) {
        EventPlanDisruptionState.NotRequested -> Unit
        EventPlanDisruptionState.Loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.one_time_event_disruptions_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EventPlanDisruptionState.Unavailable -> Text(
            stringResource(R.string.one_time_event_disruptions_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is EventPlanDisruptionState.Ready -> {
            val disruptions = state.disruptions.distinctBy { it.id ?: it.headline }
            if (disruptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    disruptions.forEach { resolved ->
                        val disruption = resolved.toPresentation()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("⚠️ ${disruption.headline}", style = MaterialTheme.typography.bodyMedium)
                                disruption.details?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannedPreviewMessage(text: String, action: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                TextButton(onClick = action) { Text(stringResource(R.string.one_time_event_planned_retry)) }
            }
        }
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
