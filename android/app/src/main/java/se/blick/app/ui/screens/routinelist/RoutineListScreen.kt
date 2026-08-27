package se.blick.app.ui.screens.routinelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import se.blick.app.R
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.ui.components.BlickHomeHeader
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.components.RoutineLabelIconContainer
import se.blick.app.ui.components.RoutineLabelPill
import se.blick.app.ui.components.visuals
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.RoutineDestructiveRed
import se.blick.app.ui.theme.StockholmNightSurfaces
import se.blick.app.ui.screens.onetimeevent.OneTimeEventCard
import se.blick.app.billing.RoutineTierPolicy
import se.blick.app.domain.model.RoutineType
import se.blick.app.locale.currentBlickLocale
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** Extra bottom padding reserved for the last list row so the always-visible FAB (see
 * [RoutineListScreen]'s `floatingActionButton` slot) never obscures it — Material3's
 * [Scaffold] only reserves space for top/bottom bars in the [PaddingValues] it hands to
 * content, not for a floating action button, which is a known Material3 Scaffold gap (the
 * FAB visually floats above the content layer). A plain FAB is ~56dp tall plus its default
 * ~16dp margin; 96dp leaves comfortable clearance without needing to measure the real FAB. */
private val FAB_CLEARANCE = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(
    onAddRoutine: () -> Unit,
    onAddEvent: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onOpenEvent: (String) -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onOpenRoutine: (String) -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: RoutineListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = rememberCurrentLocalDate()

    RoutineListContent(
        uiState = uiState,
        today = today,
        onAddRoutine = onAddRoutine,
        onAddEvent = onAddEvent,
        onOpenEvents = onOpenEvents,
        onOpenEvent = onOpenEvent,
        onOpenPremium = onOpenPremium,
        onOpenRoutine = onOpenRoutine,
        onOpenAbout = onOpenAbout,
        onSelectFreeRoutine = viewModel::selectFreeRoutine,
        onMoveRoutine = viewModel::moveRoutine,
    )
}

/** Stateless — pulled out of [RoutineListScreen] so [RoutineListViewModel]/Hilt aren't required
 * to exercise the FAB-visibility and list/empty-state behaviour in a Compose UI test (see
 * `RoutineListScreenTest`, an `androidTest` that calls this composable directly with a plain
 * [RoutineListUiState], with no `hiltViewModel()`/`HiltAndroidRule` involved at all).
 *
 * Free can create its one line/direction routine; verified Premium uses the same form with
 * the exact-destination field enabled. Locked exact routines retained after revocation do
 * not count as the Free line routine. Persistence repeats these checks as defence in depth. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListContent(
    uiState: RoutineListUiState,
    today: LocalDate = LocalDate.now(),
    onAddRoutine: () -> Unit,
    onAddEvent: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onOpenEvent: (String) -> Unit = {},
    onOpenRoutine: (String) -> Unit,
    // Defaulted (unlike onAddRoutine/onOpenRoutine) so existing RoutineListScreenTest call
    // sites, which predate the About screen, don't need to be touched just to compile --
    // there's nothing to assert about this control in those FAB/list-focused tests.
    onOpenAbout: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onSelectFreeRoutine: (String) -> Unit = {},
    onMoveRoutine: (String, String) -> Unit = { _, _ -> },
) {
    // One stable notification remains sufficient because enabled routine windows cannot overlap.
    var showOneRoutineLimitDialog by remember { mutableStateOf(false) }
    var showAddPlanSheet by remember { mutableStateOf(false) }
    val routineBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var lastDragTargetId by remember { mutableStateOf<String?>(null) }
    var draggedRoutineId by remember { mutableStateOf<String?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    val useStockholmNightHeader = LocalStockholmNightTheme.current
    val sectionLabelColor = when {
        useStockholmNightHeader -> Color(0xFF8393AA)
        MaterialTheme.colorScheme.background.luminance() < 0.5f -> Color.White
        else -> Color.Black
    }

    Scaffold(
        topBar = {
            Column {
                BlickHomeHeader(
                    useStockholmNightBranding = useStockholmNightHeader,
                    onOpenAbout = onOpenAbout,
                )
                Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(
                        text = stringResource(R.string.routine_list_title),
                        color = sectionLabelColor,
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            // Always visible. Both tiers open the same form; entitlement controls whether
            // its destination field is enabled.
            FloatingActionButton(
                onClick = { showAddPlanSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.routine_list_add))
            }
        },
    ) { paddingValues ->
        if (!uiState.isLoading && uiState.routines.isEmpty() && uiState.upcomingEvents.isEmpty()) {
            // contentAlignment centers the whole text block both axes; textAlign additionally
            // centers each wrapped line *within* that block on narrow phones, where this
            // message wraps to two or three lines -- without it, wrapped lines default to
            // start-aligned and look ragged even though the block itself is centered. The
            // horizontal padding caps the line length on wide tablets so text doesn't stretch
            // edge to edge there.
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.routine_list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                // See FAB_CLEARANCE's doc — the outer Scaffold padding above does not
                // account for the floating action button, so the list's own trailing
                // content padding must, or the last routine row can end up under the FAB.
                contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
            ) {
                itemsIndexed(uiState.routines, key = { _, routine -> routine.id }) { index, routine ->
                    val allowed = RoutineTierPolicy.canRun(
                        routine, uiState.routines, uiState.entitlement, uiState.selectedFreeRoutineId,
                    )
                    val reorderEnabled = uiState.entitlement.hasPremiumAccess
                    val moveUpLabel = stringResource(R.string.routine_list_move_up)
                    val moveDownLabel = stringResource(R.string.routine_list_move_down)
                    DisposableEffect(routine.id) {
                        onDispose { routineBounds.remove(routine.id) }
                    }
                    val reorderModifier = if (reorderEnabled) {
                        Modifier
                            .pointerInput(routine.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { localPosition ->
                                        draggedRoutineId = routine.id
                                        dragPointerY = (routineBounds[routine.id]?.top ?: 0f) + localPosition.y
                                        lastDragTargetId = null
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        draggedRoutineId = null
                                        lastDragTargetId = null
                                    },
                                    onDragCancel = {
                                        draggedRoutineId = null
                                        lastDragTargetId = null
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragPointerY += dragAmount.y
                                        val targetId = routineBounds.entries
                                            .firstOrNull { (_, bounds) -> dragPointerY in bounds.top..bounds.bottom }
                                            ?.key
                                        if (targetId == null || targetId == routine.id) {
                                            lastDragTargetId = null
                                        } else if (targetId != lastDragTargetId) {
                                            lastDragTargetId = targetId
                                            onMoveRoutine(routine.id, targetId)
                                        }
                                    },
                                )
                            }
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) {
                                        add(CustomAccessibilityAction(moveUpLabel) {
                                            onMoveRoutine(routine.id, uiState.routines[index - 1].id)
                                            true
                                        })
                                    }
                                    if (index < uiState.routines.lastIndex) {
                                        add(CustomAccessibilityAction(moveDownLabel) {
                                            onMoveRoutine(routine.id, uiState.routines[index + 1].id)
                                            true
                                        })
                                    }
                                }
                            }
                    } else {
                        Modifier
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                routineBounds[routine.id] = coordinates.boundsInRoot()
                            }
                            .then(reorderModifier)
                            .testTag("routine_reorder_item_${routine.id}"),
                    ) {
                        val isDragging = draggedRoutineId == routine.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isDragging) Modifier.testTag("routine_drag_highlight_${routine.id}") else Modifier,
                                ),
                        ) {
                            if (routine.label != null) {
                                LabeledRoutineCard(
                                    routine = routine,
                                    allowed = allowed,
                                    isPausedToday = routine.pausedDate == today,
                                    isDragging = isDragging,
                                    onOpenRoutine = onOpenRoutine,
                                    onSelectFreeRoutine = onSelectFreeRoutine,
                                )
                            } else {
                                UnlabeledRoutineCard(
                                    routine = routine,
                                    allowed = allowed,
                                    isPausedToday = routine.pausedDate == today,
                                    isDragging = isDragging,
                                    onOpenRoutine = onOpenRoutine,
                                    onSelectFreeRoutine = onSelectFreeRoutine,
                                )
                            }
                        }
                    }
                }
                if (uiState.upcomingEvents.isNotEmpty()) {
                    item(key = "upcoming-events-heading") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 22.dp, end = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.one_time_event_upcoming_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = {
                                    if (uiState.entitlement.hasPremiumAccess) onOpenEvents() else onOpenPremium()
                                },
                            ) { Text(stringResource(R.string.one_time_event_view_all)) }
                        }
                    }
                    itemsIndexed(
                        uiState.upcomingEvents.take(3),
                        key = { _, event -> "event-${event.id}" },
                    ) { _, event ->
                        OneTimeEventCard(
                            event = event,
                            onClick = {
                                if (uiState.entitlement.hasPremiumAccess) onOpenEvent(event.id) else onOpenPremium()
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            locked = !uiState.entitlement.hasPremiumAccess,
                            matchHomeRoutineCardEdges = true,
                        )
                    }
                }
            }
        }
    }

    if (showAddPlanSheet) {
        ModalBottomSheet(onDismissRequest = { showAddPlanSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.add_plan_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_plan_routine_title), fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(stringResource(R.string.add_plan_routine_subtitle)) },
                    leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAddPlanSheet = false
                        when {
                            uiState.entitlement.hasPremiumAccess -> onAddRoutine()
                            uiState.routines.none { it.type == RoutineType.LINE_DIRECTION } -> onAddRoutine()
                            else -> showOneRoutineLimitDialog = true
                        }
                    },
                )
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.add_plan_event_title), fontWeight = FontWeight.SemiBold)
                            if (!uiState.entitlement.hasPremiumAccess) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.premium_feature_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    supportingContent = { Text(stringResource(R.string.add_plan_event_subtitle)) },
                    leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAddPlanSheet = false
                        if (uiState.entitlement.hasPremiumAccess) onAddEvent() else onOpenPremium()
                    },
                )
            }
        }
    }

    if (showOneRoutineLimitDialog) {
        AlertDialog(
            onDismissRequest = { showOneRoutineLimitDialog = false },
            title = { Text(stringResource(R.string.routine_list_free_limit_title)) },
            text = { Text(stringResource(R.string.routine_list_free_limit_body)) },
            confirmButton = {
                TextButton(onClick = { showOneRoutineLimitDialog = false; onOpenPremium() }) {
                    Text(stringResource(R.string.premium_view))
                }
            },
            dismissButton = { TextButton(onClick = { showOneRoutineLimitDialog = false }) {
                Text(stringResource(R.string.action_back))
            } },
        )
    }

}

/** Keeps the home-only pause label current across local midnight without adding polling,
 * scheduling work, or another commute refresh loop. The coroutine sleeps until the next local
 * date boundary and exists only while this composable is on screen. */
@Composable
private fun rememberCurrentLocalDate(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            today = now.toLocalDate()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
        }
    }
    return today
}

@Composable
private fun UnlabeledRoutineCard(
    routine: CommuteRoutine,
    allowed: Boolean,
    isPausedToday: Boolean,
    isDragging: Boolean,
    onOpenRoutine: (String) -> Unit,
    onSelectFreeRoutine: (String) -> Unit,
) {
    if (LocalStockholmNightTheme.current) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
        ) {
            Card(
                onClick = { onOpenRoutine(routine.id) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else StockholmNightSurfaces.Card,
                ),
                border = BorderStroke(1.dp, StockholmNightSurfaces.CardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 5.dp else 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unlabeled_routine_card_${routine.id}"),
            ) {
                UnlabeledRoutineContent(
                    routine = routine,
                    allowed = allowed,
                    isPausedToday = isPausedToday,
                    onSelectFreeRoutine = onSelectFreeRoutine,
                    containerColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        UnlabeledRoutineContent(
            routine = routine,
            allowed = allowed,
            isPausedToday = isPausedToday,
            onSelectFreeRoutine = onSelectFreeRoutine,
            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
            modifier = Modifier.clickable { onOpenRoutine(routine.id) },
        )
    }
}

@Composable
private fun UnlabeledRoutineContent(
    routine: CommuteRoutine,
    allowed: Boolean,
    isPausedToday: Boolean,
    onSelectFreeRoutine: (String) -> Unit,
    containerColor: Color,
    modifier: Modifier,
) {
    ListItem(
        // The same colored line-number badge used throughout the app (route
        // selection, Routine Details, departure rows, and the home-screen
        // widget) — null only for a routine with no specific line configured,
        // matching every other display site's identical null-check.
        leadingContent = routine.lineDesignation?.let { designation ->
            { LineBadge(lineDesignation = designation, transportMode = routine.transportMode) }
        },
        // One line only: badge + "{stop} → {direction}" (routine.name's own
        // default pattern, see RoutineCreateViewModel.selectDirection) — no
        // separate supportingContent line for the site name, which would just
        // repeat it a second time since the name already includes it.
        headlineContent = { Text(routine.name) },
        // Consistent with the details screen: enabled/disabled and a current-day pause are
        // stated in words here too, never colour-only.
        supportingContent = if (!allowed) {
            { Text(stringResource(R.string.routine_list_premium_locked)) }
        } else null,
        trailingContent = when {
            !allowed && routine.type == RoutineType.LINE_DIRECTION -> ({
                TextButton(onClick = { onSelectFreeRoutine(routine.id) }) {
                    Text(stringResource(R.string.routine_list_use_free))
                }
            })
            !routine.enabled -> ({
                Text(
                    text = stringResource(R.string.routine_details_status_disabled),
                    color = RoutineDestructiveRed,
                )
            })
            isPausedToday -> ({
                Text(
                    text = stringResource(R.string.routine_list_status_paused),
                    color = pausedRoutineStatusColor(),
                )
            })
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = modifier,
    )
}

@Composable
private fun LabeledRoutineCard(
    routine: CommuteRoutine,
    allowed: Boolean,
    isPausedToday: Boolean,
    isDragging: Boolean,
    onOpenRoutine: (String) -> Unit,
    onSelectFreeRoutine: (String) -> Unit,
) {
    val label = requireNotNull(routine.label)
    val useStockholmNightSurface = LocalStockholmNightTheme.current
    val visuals = label.visuals(isSystemInDarkTheme())
    val locale = currentBlickLocale()
    val schedule = formatRoutineCardSchedule(
        routine = routine,
        locale = locale,
        everyDayLabel = stringResource(R.string.routine_details_schedule_every_day),
        weekdaysLabel = stringResource(R.string.routine_details_schedule_weekdays),
    )
    val status = when {
        !allowed -> stringResource(R.string.routine_list_premium_locked)
        !routine.enabled -> stringResource(R.string.routine_details_status_disabled)
        isPausedToday -> stringResource(R.string.routine_list_status_paused)
        else -> null
    }
    val statusColor = when {
        !allowed -> MaterialTheme.colorScheme.onSurfaceVariant
        !routine.enabled -> RoutineDestructiveRed
        else -> pausedRoutineStatusColor()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Card(
            onClick = { onOpenRoutine(routine.id) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else if (useStockholmNightSurface) {
                    StockholmNightSurfaces.Card
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            border = if (useStockholmNightSurface) {
                BorderStroke(1.dp, StockholmNightSurfaces.CardBorder)
            } else {
                null
            },
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 5.dp else 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .testTag("labeled_routine_card_${label.name.lowercase()}"),
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(visuals.accent)
                        .testTag("routine_label_strip_${label.name.lowercase()}"),
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoutineLabelIconContainer(
                        label = label,
                        containerSize = 58.dp,
                        iconSize = 30.dp,
                        cornerRadius = 16.dp,
                    )
                    Spacer(Modifier.width(15.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        RoutineLabelPill(label)
                        Text(
                            text = routine.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.DateRange,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = schedule,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        status?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                maxLines = 1,
                            )
                        }
                    }
                    if (!allowed && routine.type == RoutineType.LINE_DIRECTION) {
                        TextButton(onClick = { onSelectFreeRoutine(routine.id) }) {
                            Text(stringResource(R.string.routine_list_use_free))
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.routine_list_open, routine.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("routine_card_chevron"),
                    )
                }
            }
        }
    }
}

/** White on Stockholm Night and regular dark surfaces as requested; the light theme uses its
 * accessible on-surface color rather than rendering white text on a white card. */
@Composable
private fun pausedRoutineStatusColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else MaterialTheme.colorScheme.onSurface
