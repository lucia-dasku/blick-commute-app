package se.blick.app.ui.screens.routinelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.ui.components.BlickWordmark
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.components.RoutineLabelIconContainer
import se.blick.app.ui.components.RoutineLabelPill
import se.blick.app.ui.components.visuals
import se.blick.app.billing.RoutineTierPolicy
import se.blick.app.domain.model.RoutineType
import se.blick.app.locale.currentBlickLocale

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
    onOpenPremium: () -> Unit = {},
    onOpenRoutine: (String) -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: RoutineListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RoutineListContent(
        uiState = uiState,
        onAddRoutine = onAddRoutine,
        onOpenPremium = onOpenPremium,
        onOpenRoutine = onOpenRoutine,
        onOpenAbout = onOpenAbout,
        onSelectFreeRoutine = viewModel::selectFreeRoutine,
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
    onAddRoutine: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    // Defaulted (unlike onAddRoutine/onOpenRoutine) so existing RoutineListScreenTest call
    // sites, which predate the About screen, don't need to be touched just to compile --
    // there's nothing to assert about this control in those FAB/list-focused tests.
    onOpenAbout: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onSelectFreeRoutine: (String) -> Unit = {},
) {
    // One stable notification remains sufficient because enabled routine windows cannot overlap.
    var showOneRoutineLimitDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { BlickWordmark() },
                    actions = {
                        IconButton(onClick = onOpenAbout) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.about_action))
                        }
                    },
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(
                        text = stringResource(R.string.routine_list_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.routine_list_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            // Always visible. Both tiers open the same form; entitlement controls whether
            // its destination field is enabled.
            FloatingActionButton(
                onClick = {
                    when {
                        uiState.entitlement.hasPremiumAccess -> onAddRoutine()
                        // Locked exact-destination routines are intentionally retained after a
                        // refund. If there is no line routine among them, Free must still be
                        // able to create its one eligible line-and-direction routine.
                        uiState.routines.none { it.type == RoutineType.LINE_DIRECTION } -> onAddRoutine()
                        else -> showOneRoutineLimitDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.routine_list_add))
            }
        },
    ) { paddingValues ->
        if (!uiState.isLoading && uiState.routines.isEmpty()) {
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
                items(uiState.routines, key = CommuteRoutine::id) { routine ->
                    val allowed = RoutineTierPolicy.canRun(
                        routine, uiState.routines, uiState.entitlement, uiState.selectedFreeRoutineId,
                    )
                    if (routine.label != null) {
                        LabeledRoutineCard(
                            routine = routine,
                            allowed = allowed,
                            onOpenRoutine = onOpenRoutine,
                            onSelectFreeRoutine = onSelectFreeRoutine,
                        )
                    } else {
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
                        // Consistent with the details screen: enabled/disabled is always
                        // stated in words here too, never colour-only (see
                        // RoutineDetailsScreen's statusLabel for the same rule; "paused
                        // today" isn't surfaced here since it requires the injected Clock,
                        // which this list-only screen has no other reason to depend on).
                        supportingContent = if (!allowed) {
                            { Text(stringResource(R.string.routine_list_premium_locked)) }
                        } else null,
                        trailingContent = when {
                            !allowed && routine.type == RoutineType.LINE_DIRECTION -> ({
                                TextButton(onClick = { onSelectFreeRoutine(routine.id) }) {
                                    Text(stringResource(R.string.routine_list_use_free))
                                }
                            })
                            !routine.enabled -> ({ Text(stringResource(R.string.routine_details_status_disabled)) })
                            else -> null
                        },
                        modifier = Modifier.clickable { onOpenRoutine(routine.id) },
                        )
                    }
                }
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

@Composable
private fun LabeledRoutineCard(
    routine: CommuteRoutine,
    allowed: Boolean,
    onOpenRoutine: (String) -> Unit,
    onSelectFreeRoutine: (String) -> Unit,
) {
    val label = requireNotNull(routine.label)
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
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Card(
            onClick = { onOpenRoutine(routine.id) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
