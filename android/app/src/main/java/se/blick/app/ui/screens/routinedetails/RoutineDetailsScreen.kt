@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinedetails

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.domain.model.DisruptionPresentation
import se.blick.app.domain.model.ResolvedJourneyDisruption
import se.blick.app.domain.model.toPresentation
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.usecase.countdownMinutes
import se.blick.app.domain.usecase.effectiveFirstDeparture
import se.blick.app.domain.usecase.filterCurrentJourneys
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.JOURNEY_TRANSPORT_MODE_OPTIONS
import se.blick.app.domain.model.RoutineType
import se.blick.app.ui.theme.RoutineDestructiveRed
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import se.blick.app.locale.currentBlickLocale
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationPostResult
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.notification.notificationSettingsIntent
import se.blick.app.ui.notification.rememberNotificationPermissionGate
import se.blick.app.widget.LINE_BADGE_GREEN

/**
 * Routine details / live-preview screen: loads one saved routine and shows its next two
 * relevant departures via [RoutineDetailsViewModel] + the existing live-departure engine.
 * While this screen is open it automatically refreshes about every 30 seconds (independent
 * of the separate ~30s ongoing-notification loop driven by `scheduling/RoutineActiveWindowWorker`),
 * plus a manual Refresh action; a notification-status hint on this screen (see
 * [NotificationStatusRow]) also re-checks availability every time the screen resumes, e.g.
 * after returning from system notification settings.
 *
 * Also hosts routine management. Pause/resume today ([PauseTodayButton]) sits right under the
 * departures list, since it directly affects what that list is showing; edit (delegates
 * navigation to [onEdit], the actual editing UI is
 * [se.blick.app.ui.screens.routinecreate.RoutineCreateScreen] reused in edit mode — see
 * [se.blick.app.ui.navigation.BlickNavHost]), enable/disable, and delete (with an in-screen
 * confirmation dialog; [onDeleted] is only invoked once the repository write actually
 * succeeds) live inside the collapsed-by-default [RoutineActionsSection] further down instead.
 *
 * In debug builds only, also hosts a manual "Show/update test notification" /
 * "Remove test notification" pair (see [DebugNotificationSection] and
 * [RoutineDetailsViewModel.showDebugTestNotification]) for exercising the real
 * `notification/AndroidRoutineNotifier` directly, alongside the automatic scheduled loop.
 */
@Composable
fun RoutineDetailsScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    onDeleted: () -> Unit = onBack,
    viewModel: RoutineDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Drives RoutineDetailsViewModel.runAutoRefresh's 30-second loop for exactly as long as
    // this screen is visible and STARTED (see that function's own doc) -- repeatOnLifecycle
    // cancels its block (stopping the loop) on STOP and re-runs it (an immediate fetch, then
    // resumed 30-second ticks) on the next STARTED, so backgrounding the app, navigating away,
    // or a screen rotation all behave correctly with no separate stop/restart wiring needed
    // here, and repeatOnLifecycle's own guarantee that only one of its blocks runs at a time
    // rules out a duplicate concurrent loop from rapid recomposition.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.runAutoRefresh()
        }
    }

    Scaffold(
        topBar = {
            // No title -- the journeys/departures heading right below already identifies this
            // screen by its own route/routine name (see JourneyComparisonSection's own call
            // site), so a second, generic "Routine" label up here was redundant.
            BlickTopBar(title = null, onBack = onBack)
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
                disruptionsState = uiState.disruptions,
                journeys = uiState.journeys,
                journeysUnavailable = uiState.journeysUnavailable,
                exactDestinationDeviationNotices = uiState.exactDestinationDeviationNotices,
                // The ViewModel's own journeysEvaluatedAt, not the default Instant.now() --
                // see that field's own doc, and RoutineDetailsContent's now parameter doc, for
                // why production must never rely on the default here.
                now = uiState.journeysEvaluatedAt,
                isUpdatingJourneyTransportModes = uiState.isUpdatingJourneyTransportModes,
                journeyTransportModesUpdateFailed = uiState.journeyTransportModesUpdateFailed,
                onUpdateJourneyTransportModes = viewModel::updateJourneyTransportModes,
                isUpdatingChangesPreference = uiState.isUpdatingChangesPreference,
                changesPreferenceUpdateFailed = uiState.changesPreferenceUpdateFailed,
                onUpdateChangesPreference = viewModel::updateChangesPreference,
                onRefresh = viewModel::refresh,
                onEdit = { onEdit(routine.id) },
                isTogglingEnabled = uiState.isTogglingEnabled,
                enabledActionFailed = uiState.enabledActionFailed,
                hasSeenNotificationRationale = uiState.hasSeenNotificationRationale,
                onNotificationRationaleSeen = viewModel::markNotificationRationaleSeen,
                notificationAvailability = uiState.notificationAvailability,
                onToggleEnabled = viewModel::toggleEnabled,
                isTogglingPause = uiState.isTogglingPause,
                pauseActionFailed = uiState.pauseActionFailed,
                onPauseToday = viewModel::pauseToday,
                onResumeToday = viewModel::resumeToday,
                isDeleting = uiState.isDeleting,
                deleteFailed = uiState.deleteFailed,
                onRequestDelete = { showDeleteConfirmation = true },
                schedulingFailed = uiState.schedulingFailed,
                isRetryingScheduling = uiState.isRetryingScheduling,
                onRetryScheduling = viewModel::retryScheduling,
                // A lambda, not a bare method reference: showDebugTestNotification's own
                // debugEffectOverride parameter defaults to null (the real disruption) when
                // called with no arguments, but a callable reference always reflects the full
                // declared parameter list regardless of defaults, so `viewModel::showDebugTestNotification`
                // alone would not type-check against this zero-argument callback.
                onShowDebugNotification = { viewModel.showDebugTestNotification() },
                onShowDebugNotificationForEffect = viewModel::showDebugTestNotification,
                onRemoveDebugNotification = viewModel::removeDebugTestNotification,
                isLiveUpdatePromotable = viewModel::isLiveUpdatePromotable,
            )
        }
    }

    if (showDeleteConfirmation) {
        val routineToDelete = uiState.routine
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.routine_details_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.routine_details_delete_dialog_body,
                        routineToDelete?.name.orEmpty(),
                        routineToDelete?.siteName.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteRoutine(onDeleted)
                    },
                ) { Text(stringResource(R.string.routine_details_delete_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.routine_details_delete_dialog_cancel))
                }
            },
        )
    }
}

/** "{origin} → {destination}" -- the same pattern the Route detail row further down this screen
 * already builds (see that row's own comment) -- reused here as the journeys section's own
 * heading, and shown nowhere near a composable so it doesn't need to be one itself. */
private fun exactDestinationRouteLabel(routine: CommuteRoutine) =
    "${routine.journeyOriginName ?: routine.siteName} → ${routine.journeyDestinationName.orEmpty()}"

@Composable
private fun JourneyComparisonSection(
    journeys: List<JourneyPlan>,
    unavailable: Boolean,
    now: Instant,
    changesPreference: ExactDestinationChangesPreference,
    isUpdatingChangesPreference: Boolean,
    changesPreferenceUpdateFailed: Boolean,
    onChangesPreferenceChange: (ExactDestinationChangesPreference) -> Unit,
) {
    // Render-time eligibility filter: a journey that was still current when fetched can have
    // since departed by the time this composable actually renders (a fresh 30-second fetch, or
    // simply a later recomposition of an already-fetched list). Filtering here, before deciding
    // between the "no journeys" state and the card list, is what keeps an expired journey from
    // ever being labeled "FASTEST"/"Alternative" or showing its line badge, arrival, changes, or
    // countdown -- countdownMinutes below is therefore never called with a past departure.
    val currentJourneys = journeys.filterCurrentJourneys(now)

    // The PERSISTED routine preference is the single source of truth for which chips read
    // selected -- see ExactDestinationChangesPreference's own doc. This filter is otherwise
    // redundant with the backend's own preference-narrowed eligible pool (see
    // GetRankedJourneysUseCase/backend/src/services/candidateCollector.ts's own doc) once a fresh
    // fetch reflecting a just-changed preference has landed, but is kept as the same defensive,
    // render-time re-filter this section already applied for currency above -- and gives
    // immediate visual feedback for the brief window between tapping a chip and that fresh fetch
    // actually completing, rather than showing a stale, no-longer-matching journey until then.
    val showDirect = changesPreference.includesDirect()
    val showWithChanges = changesPreference.includesWithChanges()
    val filteredJourneys = currentJourneys.filter { journey ->
        (showDirect && journey.transferCount == 0) || (showWithChanges && journey.transferCount > 0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JourneyFilterRow(
            showDirect = showDirect,
            showWithChanges = showWithChanges,
            // Disabled (not just ignored) while a write is in flight -- the ViewModel's own
            // isUpdatingChangesPreference guard already refuses an overlapping second write (see
            // RoutineDetailsViewModel.updateChangesPreference's own doc), but a still-tappable
            // chip that silently no-ops reads as broken rather than busy.
            enabled = !isUpdatingChangesPreference,
            // toggleDirect/toggleWithChanges already refuse to leave both chips unselected
            // (returning the unchanged preference instead) -- see their own doc.
            onToggleDirect = { onChangesPreferenceChange(changesPreference.toggleDirect()) },
            onToggleWithChanges = { onChangesPreferenceChange(changesPreference.toggleWithChanges()) },
        )
        // Same inline error idiom as JourneyTransportModesRow's own updateFailed text below --
        // routine.changesPreference (what showDirect/showWithChanges above are still derived
        // from) is left exactly as last persisted on a failed write, never an optimistic value,
        // so this is purely an explanation of why the chips didn't move, with a normal retry tap
        // available immediately (updateChangesPreference clears this flag on the next attempt).
        if (changesPreferenceUpdateFailed) {
            Text(
                stringResource(R.string.routine_details_changes_preference_update_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // DIRECT_ONLY with nothing eligible to show -- either the backend's own preference-
        // narrowed search (see candidateCollector.ts's requestMaxChanges) genuinely found no
        // direct journey, or (the brief window between tapping a chip and that fresh fetch
        // landing) currentJourneys still holds a since-superseded batch this render-time filter
        // now excludes -- gets a distinct, actionable message instead of the generic no-journeys
        // one below: the user has actively asked for direct-only, and there is a concrete
        // suggestion to offer (with changes might help), never a claim that one definitely
        // exists.
        val directOnlyEmpty = (currentJourneys.isEmpty() || filteredJourneys.isEmpty()) &&
            changesPreference == ExactDestinationChangesPreference.DIRECT_ONLY
        when {
            unavailable -> Text(stringResource(R.string.routine_details_journeys_unavailable), color = MaterialTheme.colorScheme.error)
            directOnlyEmpty -> Text(stringResource(R.string.journey_no_direct_available))
            currentJourneys.isEmpty() -> Text(stringResource(R.string.routine_details_no_journeys))
            filteredJourneys.isEmpty() -> Text(stringResource(R.string.routine_details_no_journeys))
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val fastestArrival = filteredJourneys.first().arrivalTime
                // Up to three cards: the backend already caps a routine's own journeys at
                // PRIMARY + ALTERNATIVE? + NEXT? (see backend/src/routes/journeys.ts's own doc),
                // so this cap is a defensive backstop, not a UI-driven truncation -- unlike the
                // old two-card limit, a genuine gap-filling ALTERNATIVE must never push the
                // regular NEXT departure off screen here (the widget/notification are the
                // surfaces that only ever want two rows, not this screen -- see their own code).
                filteredJourneys.take(3).forEachIndexed { index, journey ->
                    var expanded by remember(journey.journeyId) { mutableStateOf(false) }
                    Surface(
                        tonalElevation = if (index == 0) 3.dp else 1.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Labelled from the journey's OWN role, never list position: the
                                // Direct/With-changes filters above can leave any single journey as
                                // the only (and therefore first-shown) card, and it must still say
                                // what it actually is rather than default to "FASTEST" purely by
                                // virtue of being shown first (see JourneyFilterRow's own doc, and
                                // the product spec's "filtering cannot cause misleading role
                                // labels" requirement).
                                Text(
                                    stringResource(
                                        when (journey.role) {
                                            JourneyRole.PRIMARY -> R.string.journey_fastest
                                            JourneyRole.NEXT -> R.string.journey_next
                                            JourneyRole.ALTERNATIVE -> R.string.journey_alternative
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Icon(
                                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(
                                        if (expanded) R.string.journey_collapse else R.string.journey_expand,
                                    ),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                journey.firstLeg.lineDesignation?.let {
                                    LineBadge(it, journey.firstLeg.transportMode)
                                }
                                Text(
                                    stringResource(journey.firstLeg.transportMode.journeyLabelResId()),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                // countdownMinutes, never a floor-based Duration.toMinutes().coerceAtLeast(0):
                                // that would floor a genuinely-upcoming departure under a minute away down
                                // to "0 min" (indistinguishable from one that already departed) and would
                                // hide an already-expired departure as "0 min" rather than it having already
                                // been removed by the filter above. effectiveFirstDeparture, not the raw
                                // top-level departureTime, for both this eligibility check and this
                                // countdown -- see that function's own doc.
                                val minutes = countdownMinutes(now, journey.effectiveFirstDeparture())
                                Text(
                                    stringResource(R.string.journey_departure_in, formatJourneyMinutes(minutes)),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            val changes = if (journey.transferCount == 0) stringResource(R.string.journey_direct)
                                else stringResource(R.string.journey_changes, journey.transferCount)
                            val arrival = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(journey.arrivalTime)
                            val later = Duration.between(fastestArrival, journey.arrivalTime).toMinutes()
                            Text(if (index == 0) "$changes · ${stringResource(R.string.journey_arrives, arrival)}"
                                else "$changes · ${stringResource(R.string.journey_arrives, arrival)} · ${stringResource(R.string.journey_later, formatJourneyMinutes(later))}")
                            if (expanded) {
                                journey.legs.forEach { leg ->
                                    Text("${leg.originName} → ${leg.destinationName}${leg.lineDesignation?.let { " · $it" }.orEmpty()}")
                                    leg.disruptions.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            // Total journey time -- always the true bottom of the card, after the
                            // expanded leg breakdown when shown (the chevron in the header row
                            // above is the only collapsed-state expand affordance now). The same
                            // effectiveFirstDeparture the countdown above is measured from, not
                            // the raw top-level departureTime, so this duration is consistent
                            // with the departure this card actually displays.
                            val durationMinutes = Duration.between(journey.effectiveFirstDeparture(), journey.arrivalTime).toMinutes()
                            Text(
                                "⏱ ${formatJourneyMinutes(durationMinutes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatJourneyMinutes(minutes: Long): String {
    if (minutes < 60) return stringResource(R.string.journey_duration_minutes, minutes)

    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0L) {
        stringResource(R.string.journey_duration_hours, hours)
    } else {
        stringResource(R.string.journey_duration_hours_minutes, hours, remainingMinutes)
    }
}

/** Independently toggleable Direct / With changes filters over the journeys
 * [JourneyComparisonSection] shows -- both default selected (see that composable's own state),
 * so the initial view is unfiltered. Each chip shows a checkmark while selected, Material3's own
 * standard [FilterChip] affordance for "this option is currently active". */
@Composable
private fun JourneyFilterRow(
    showDirect: Boolean,
    showWithChanges: Boolean,
    enabled: Boolean,
    onToggleDirect: () -> Unit,
    onToggleWithChanges: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = showDirect,
            onClick = onToggleDirect,
            enabled = enabled,
            label = { Text(stringResource(R.string.journey_direct)) },
            leadingIcon = if (showDirect) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
        )
        FilterChip(
            selected = showWithChanges,
            onClick = onToggleWithChanges,
            enabled = enabled,
            label = { Text(stringResource(R.string.journey_with_changes)) },
            leadingIcon = if (showWithChanges) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
        )
    }
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun RoutineDetailsContent(
    modifier: Modifier,
    routine: CommuteRoutine,
    isPausedToday: Boolean,
    departuresState: LiveDeparturesState,
    isRefreshing: Boolean,
    disruptionsState: DisruptionsState,
    journeys: List<JourneyPlan> = emptyList(),
    journeysUnavailable: Boolean = false,
    /** The backend's own fully resolved, deduplicated exact-destination disruption list for the
     * current PRIMARY journey -- see [RoutineDetailsViewModel.loadJourneyDisruptionRelevance]'s
     * own doc. Already combines [journeys]' own Journey Planner notices with structurally-matched
     * SL Deviations; no further combination happens here. */
    exactDestinationDeviationNotices: List<ResolvedJourneyDisruption> = emptyList(),
    /** The "now" [JourneyComparisonSection] filters and computes countdowns against.
     * [RoutineDetailsScreen] (the only production call site) always passes
     * `uiState.journeysEvaluatedAt` here — never relies on this parameter's own default -- see
     * that field's own doc: it is what makes an otherwise-identical automatic refresh actually
     * reach Compose at all, so recomposition (and therefore this parameter's own value) advancing
     * is a direct consequence of the ViewModel's own ~30-second fetch loop completing, not of a
     * separate render-time clock read. The `Instant.now()` default exists only so a test that
     * doesn't care about the exact instant (e.g. `RoutineDetailsScreenTest` cases unrelated to
     * journeys) isn't forced to supply one. */
    now: Instant = Instant.now(),
    isUpdatingJourneyTransportModes: Boolean = false,
    journeyTransportModesUpdateFailed: Boolean = false,
    onUpdateJourneyTransportModes: (Set<TransportMode>) -> Unit = {},
    isUpdatingChangesPreference: Boolean = false,
    changesPreferenceUpdateFailed: Boolean = false,
    onUpdateChangesPreference: (ExactDestinationChangesPreference) -> Unit = {},
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    isTogglingEnabled: Boolean,
    enabledActionFailed: Boolean,
    hasSeenNotificationRationale: Boolean,
    onNotificationRationaleSeen: () -> Unit,
    notificationAvailability: NotificationAvailability,
    onToggleEnabled: () -> Unit,
    isTogglingPause: Boolean,
    pauseActionFailed: Boolean,
    onPauseToday: () -> Unit,
    onResumeToday: () -> Unit,
    isDeleting: Boolean,
    deleteFailed: Boolean,
    onRequestDelete: () -> Unit,
    schedulingFailed: Boolean,
    isRetryingScheduling: Boolean,
    onRetryScheduling: () -> Unit,
    onShowDebugNotification: () -> NotificationPostResult?,
    onShowDebugNotificationForEffect: (DisruptionEffect) -> NotificationPostResult? = { onShowDebugNotification() },
    onRemoveDebugNotification: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) {
    // currentBlickLocale() reacts to language/configuration changes and normalizes them to
    // Blick's effective English/Svenska presentation locale -- see that function's own doc.
    val locale = currentBlickLocale()

    // EXACT_DESTINATION's own top-section relevance: the backend's own fully resolved
    // disruption list for whichever journey was PRIMARY at the time of the last successful
    // lookup -- never NEXT's or ALTERNATIVE's own (see RoutineActiveWindowWorker's own doc on
    // why PRIMARY alone decides live relevance). See RoutineDetailsViewModel.loadJourneyDisruptionRelevance's
    // own doc for how this is kept following PRIMARY across a refresh (a fresh lookup is
    // triggered every time journeys reloads, guarded against a superseded in-flight request).
    // Empty (and therefore this section skipped below, same as LINE_DIRECTION's own
    // NoDisruptions case) whenever there is no current PRIMARY, it simply has no relevant
    // disruption, or the lookup itself hasn't completed/failed.
    val exactDestinationPrimaryNotices = if (routine.type == RoutineType.EXACT_DESTINATION) {
        exactDestinationDeviationNotices
    } else {
        emptyList()
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Shown first, above everything else including the routine's own name -- a relevant
        // disruption is the whole reason someone taps the notification or widget to "see more"
        // (see RoutineNotificationBuilder.contentIntent / RoutineWidgetTapIntent, which both
        // land here), so it must be the first thing visible without any scrolling, not buried
        // below routine details/actions/departures. Skipped entirely once a fetch has actually
        // completed and found nothing relevant: a "Disruptions" heading over an empty/"none"
        // message is noise once that's confirmed, not useful signal. For LINE_DIRECTION, Loading
        // and Unavailable are each still shown -- neither one means "no disruptions", just "don't
        // know yet" / "couldn't check"; EXACT_DESTINATION has no separate loading/unavailable
        // state of its own here at all (its notices arrive as part of the same journeys fetch the
        // departures section below already renders its own loading/failure state for), so it is
        // gated purely on "PRIMARY currently has at least one notice".
        val showDisruptionsSection = when (routine.type) {
            RoutineType.LINE_DIRECTION -> disruptionsState !is DisruptionsState.NoDisruptions
            RoutineType.EXACT_DESTINATION -> exactDestinationPrimaryNotices.isNotEmpty()
        }
        if (showDisruptionsSection) {
            // "this station and line" (the LINE_DIRECTION heading) does not describe an
            // exact-destination journey -- a distinct heading, not a rename of the shared one, so
            // LINE_DIRECTION's own wording stays exactly as it already reads today.
            val heading = if (routine.type == RoutineType.EXACT_DESTINATION) {
                R.string.routine_details_disruptions_heading_exact_destination
            } else {
                R.string.routine_details_disruptions_heading
            }
            Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (routine.type == RoutineType.EXACT_DESTINATION) {
                DisruptionsList(exactDestinationPrimaryNotices.map { it.toPresentation() })
            } else {
                DisruptionsSection(disruptionsState)
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
        }

        // Live departures come right after disruptions (or first, if there are none) -- the
        // other reason someone opens this screen from the notification/widget, ahead of the
        // routine's own (static, rarely-checked) name/schedule details and management actions
        // below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Exact-destination: the route itself ("{origin} → {destination}") rather than a
            // generic "Fastest journeys" label -- this is now the first thing identifying which
            // routine's journeys these are at all, since the top app bar above no longer carries
            // a title (see BlickTopBar's own call site). LINE_DIRECTION keeps its existing
            // generic "Next departures" heading -- that type's own route is already named by
            // routine.name itself, shown nowhere near this heading either way.
            Text(
                if (routine.type == RoutineType.EXACT_DESTINATION) exactDestinationRouteLabel(routine)
                else stringResource(R.string.routine_details_departures_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onRefresh, enabled = !isRefreshing) {
                Text(stringResource(R.string.routine_details_refresh_action))
            }
        }
        if (isRefreshing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))

        if (routine.type == RoutineType.EXACT_DESTINATION) {
            JourneyComparisonSection(
                journeys = journeys,
                unavailable = journeysUnavailable,
                now = now,
                changesPreference = routine.changesPreference,
                isUpdatingChangesPreference = isUpdatingChangesPreference,
                changesPreferenceUpdateFailed = changesPreferenceUpdateFailed,
                onChangesPreferenceChange = onUpdateChangesPreference,
            )
        } else {
            DeparturesSection(departuresState, routine.transportMode, locale, onRefresh)
        }

        // Pause/resume today lives here, directly under the departures it affects, rather than
        // inside the (now collapsible) Manage routine section below -- see PauseTodayButton's
        // own doc for why this is a top-level composable of its own rather than folded back
        // into RoutineActionsSection.
        Spacer(Modifier.height(12.dp))
        PauseTodayButton(
            isPausedToday = isPausedToday,
            isTogglingPause = isTogglingPause,
            pauseActionFailed = pauseActionFailed,
            onPauseToday = onPauseToday,
            onResumeToday = onResumeToday,
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // A fixed section heading rather than routine.name -- the routine's own name/route is
        // still fully identifiable below via the Direction row's own "{siteName} → {destination}"
        // value (see that row's own comment), just no longer repeated up here too. The same
        // titleMedium size as this screen's other two section headings ("Next departures",
        // "Manage routine"), rather than the larger headlineSmall inherited from when this line
        // showed the routine's own name as a prominent heading.
        Text(stringResource(R.string.routine_details_info_heading), style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(12.dp))
        if (routine.type == RoutineType.EXACT_DESTINATION) {
            JourneyTransportModesRow(
                selectedModes = routine.allowedJourneyTransportModes,
                isSaving = isUpdatingJourneyTransportModes,
                updateFailed = journeyTransportModesUpdateFailed,
                onSave = onUpdateJourneyTransportModes,
            )
        } else {
            DetailRow(stringResource(R.string.routine_details_mode_label), stringResource(routine.transportMode.detailsLabelResId()))
        }
        routine.lineDesignation?.let { designation ->
            LineDetailRow(stringResource(R.string.routine_details_line_label), designation, routine.transportMode)
        }
        if (routine.type == RoutineType.EXACT_DESTINATION) {
            // Exact-destination routines never populate destinationLabel (that field is a
            // LINE_DIRECTION-only concept -- the destination printed on a physical vehicle's
            // signage) -- their own origin/destination instead live in journeyOriginName/
            // journeyDestinationName. Labeled "Route" rather than reusing "Direction": this
            // shows the whole origin-to-destination path, whereas "destination" on its own
            // reads as just the single point the user gets off at.
            DetailRow(
                stringResource(R.string.routine_details_route_label),
                "${routine.journeyOriginName ?: routine.siteName} → ${routine.journeyDestinationName.orEmpty()}",
            )
        } else {
            // "{siteName} → {destination}", the same default pattern routine.name itself is built
            // from (see RoutineCreateViewModel.selectDirection) -- now the one place on this screen
            // that spells out the full route, since the heading above no longer does.
            routine.destinationLabel?.let { destination ->
                DetailRow(stringResource(R.string.routine_details_direction_label), "${routine.siteName} → $destination")
            }
        }
        DetailRow(
            stringResource(R.string.routine_details_schedule_label),
            formatActiveDays(
                routine.activeDays,
                locale,
                everyDayLabel = stringResource(R.string.routine_details_schedule_every_day),
                weekdaysLabel = stringResource(R.string.routine_details_schedule_weekdays),
            ),
        )
        DetailRow(stringResource(R.string.routine_details_time_label), formatTimeRange(routine.startTime, routine.endTime, locale))
        DetailRow(
            stringResource(R.string.routine_details_status_label),
            statusLabel(routine, isPausedToday),
            // Only Enabled/Disabled get a dot -- Paused today (a third, distinct status) keeps
            // its existing plain-text-only rendering, unchanged.
            dotColor = when {
                isPausedToday -> null
                routine.enabled -> LINE_BADGE_GREEN
                else -> RoutineDestructiveRed
            },
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        RoutineActionsSection(
            routine = routine,
            onEdit = onEdit,
            isTogglingEnabled = isTogglingEnabled,
            enabledActionFailed = enabledActionFailed,
            hasSeenNotificationRationale = hasSeenNotificationRationale,
            onNotificationRationaleSeen = onNotificationRationaleSeen,
            notificationAvailability = notificationAvailability,
            isLiveUpdatePromotable = isLiveUpdatePromotable,
            onToggleEnabled = onToggleEnabled,
            isDeleting = isDeleting,
            deleteFailed = deleteFailed,
            onRequestDelete = onRequestDelete,
            schedulingFailed = schedulingFailed,
            isRetryingScheduling = isRetryingScheduling,
            onRetryScheduling = onRetryScheduling,
        )

        // The debug source set renders the manual notification tools here; the release
        // implementation emits no UI and has no developer resources.
        DebugNotificationSection(
            canShow = departuresState !is LiveDeparturesState.Loading,
            onShow = onShowDebugNotification,
            onShowForEffect = onShowDebugNotificationForEffect,
            onRemove = onRemoveDebugNotification,
            isLiveUpdatePromotable = isLiveUpdatePromotable,
        )
    }
}

/**
 * Debug-only UI for manually verifying [se.blick.app.notification.RoutineNotifier] end to
 * end before any scheduler exists to call it automatically (see
 * [RoutineDetailsViewModel.showDebugTestNotification]/`removeDebugTestNotification`). Handles
 * its own minimal, debug-only `POST_NOTIFICATIONS` runtime-permission request on API 33+ —
 * deliberately independent of `AppSettingsDataStore.hasSeenNotificationRationale` and the
 * still-unbuilt production rationale screen; this is only for exercising the notifier itself.
 *
 * [onShow] returns the notifier's real [NotificationPostResult] (or null if there was no
 * routine loaded to post for), and the displayed message is derived from that actual result
 * via [NotificationPostResult.toDebugMessage] — granting the permission is necessary but not
 * sufficient to report success; posting itself must also have actually succeeded. On an
 * actual [NotificationPostResult.Posted], [isLiveUpdatePromotable] is also checked and
 * appended, so this section doubles as a way to check platform-level promotion *eligibility*
 * without needing a real Android 16 lock screen — but see
 * [se.blick.app.notification.PromotedNotificationChecker]'s own doc for why "eligible" is not
 * the same as "will actually render," and cannot substitute for real device verification.
 */
@Composable
private fun DebugNotificationSection(
    canShow: Boolean,
    onShow: () -> NotificationPostResult?,
    onShowForEffect: (DisruptionEffect) -> NotificationPostResult?,
    onRemove: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) {
    DebugNotificationToolsContent(
        canShow = canShow,
        onShow = onShow,
        onShowForEffect = onShowForEffect,
        onRemove = onRemove,
        isLiveUpdatePromotable = isLiveUpdatePromotable,
    )
}

/**
 * "Pause today"/"Resume today" -- its own top-level composable (used directly by
 * [RoutineDetailsContent], right under the departures it affects) rather than folded back into
 * [RoutineActionsSection] below, which now only holds the collapsible edit/disable/delete
 * group. Text/enabled/error-message behaviour is exactly what lived inside
 * [RoutineActionsSection] before this split -- only its position on screen changed.
 */
@Composable
private fun PauseTodayButton(
    isPausedToday: Boolean,
    isTogglingPause: Boolean,
    pauseActionFailed: Boolean,
    onPauseToday: () -> Unit,
    onResumeToday: () -> Unit,
) {
    Column {
        Button(
            onClick = if (isPausedToday) onResumeToday else onPauseToday,
            enabled = !isTogglingPause,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (isPausedToday) R.string.routine_details_resume_today_action else R.string.routine_details_pause_today_action,
                ),
            )
        }
        if (pauseActionFailed) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.routine_details_pause_action_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Collapsible edit/disable/delete group -- collapsed by default, showing only the heading, a
 * fixed one-line description, and a chevron (see [routine_details_actions_description][R.string.routine_details_actions_description]);
 * the whole header row is the tap target, not just the chevron icon, so [expanded] toggles from
 * a click anywhere across the heading+description block, matching the same
 * collapsed-header/expand-on-tap shape [DisruptionRow] already uses elsewhere on this screen.
 * Pause/resume today is deliberately NOT part of this group any more -- see [PauseTodayButton],
 * now a sibling composable placed directly under the departures list instead.
 *
 * Every action inside, once expanded, keeps its exact pre-existing behaviour and confirmation
 * dialog (delete, handled by the caller via [onRequestDelete]) -- this composable only changes
 * what's visible before the user taps to expand it, never what any individual action itself
 * does.
 */
@Composable
private fun RoutineActionsSection(
    routine: CommuteRoutine,
    onEdit: () -> Unit,
    isTogglingEnabled: Boolean,
    enabledActionFailed: Boolean,
    hasSeenNotificationRationale: Boolean,
    onNotificationRationaleSeen: () -> Unit,
    notificationAvailability: NotificationAvailability,
    isLiveUpdatePromotable: () -> Boolean,
    onToggleEnabled: () -> Unit,
    isDeleting: Boolean,
    deleteFailed: Boolean,
    onRequestDelete: () -> Unit,
    schedulingFailed: Boolean,
    isRetryingScheduling: Boolean,
    onRetryScheduling: () -> Unit,
) {
    // Enabling a routine is exactly the "appropriate user-driven point" the product doc asks
    // for to request POST_NOTIFICATIONS (see rememberNotificationPermissionGate's own doc) --
    // disabling never needs it, so the gate only wraps the enabling direction below.
    val notifyGate = rememberNotificationPermissionGate(hasSeenNotificationRationale, onNotificationRationaleSeen)
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.routine_details_actions_heading), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.routine_details_actions_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.routine_details_actions_collapse else R.string.routine_details_actions_expand,
                ),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))

            // A shared signal across enable/disable, pause/resume, and reload -- see
            // RoutineDetailsUiState.schedulingFailed's own doc on why this is deliberately
            // separate from enabledActionFailed (which only ever means the Room write itself
            // failed): the persisted change above is already correct either way, only its
            // WorkManager scheduling needs a retry.
            if (schedulingFailed) {
                Text(
                    stringResource(R.string.routine_details_scheduling_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRetryScheduling,
                    enabled = !isRetryingScheduling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (routine.enabled) {
                NotificationStatusRow(notificationAvailability)
                Spacer(Modifier.height(8.dp))
                if (notificationAvailability == NotificationAvailability.Available) {
                    LiveUpdatePromotionRow(isLiveUpdatePromotable())
                    Spacer(Modifier.height(8.dp))
                }
            }

            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.routine_details_edit_action))
            }
            Spacer(Modifier.height(8.dp))

            // Never colour-only: the label itself always states the resulting/current state in
            // words (see the milestone requirement on text scaling + no colour-only status).
            Button(
                onClick = {
                    if (routine.enabled) onToggleEnabled() else notifyGate { onToggleEnabled() }
                },
                enabled = !isTogglingEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (routine.enabled) R.string.routine_details_disable_action else R.string.routine_details_enable_action,
                    ),
                )
            }
            if (enabledActionFailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.routine_details_enable_action_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onRequestDelete,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RoutineDestructiveRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.routine_details_delete_action))
            }
            if (deleteFailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.routine_details_delete_action_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Reflects the REAL, current [NotificationAvailability] (see that type's own doc — the exact
 * same shared checker [se.blick.app.notification.AndroidRoutineNotifier] and
 * `RoutineActiveWindowWorker` read before ever posting) — so this can never claim automatic
 * notification delivery is active when it actually is not (see the product doc's "Production
 * notification permission" requirement).
 *
 * Deliberately a plain parameter, not a locally-`remember`ed snapshot: the value is supplied by
 * [RoutineDetailsViewModel.notificationAvailability], which that ViewModel re-checks every time
 * the screen becomes active again (see [RoutineDetailsViewModel.refreshNotificationAvailability]'s
 * doc) — returning from system Settings, a permission-result callback, or any other change is
 * therefore always reflected on the very next recomposition once the lifecycle resumes, rather
 * than staying frozen at whatever the very first read happened to be for the composable's whole
 * lifetime (the bug a `remember(context)` snapshot here used to have).
 */
@Composable
private fun NotificationStatusRow(notificationAvailability: NotificationAvailability) {
    val context = LocalContext.current
    val deliveryActive = notificationAvailability == NotificationAvailability.Available

    Column {
        Text(
            stringResource(
                if (deliveryActive) {
                    R.string.routine_details_notifications_active_hint
                } else {
                    R.string.routine_details_notifications_disabled_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (deliveryActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        if (!deliveryActive) {
            TextButton(onClick = { context.startActivity(notificationSettingsIntent(context)) }) {
                Text(stringResource(R.string.notification_settings_open_action))
            }
        }
    }
}

/**
 * Pure gating decision for [LiveUpdatePromotionRow], pulled out of the composable so it can be
 * unit-tested in a plain JVM test without Robolectric — this project's Robolectric pin
 * (`@Config(sdk = [34])`, see `libs.versions.toml`'s `robolectric` entry) can't exercise
 * `Build.VERSION_CODES.BAKLAVA` (36) behavior directly, so [sdkInt] is passed in rather than
 * read from [Build.VERSION.SDK_INT] internally. `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`
 * is an Android 16+ system screen; below that, [isLiveUpdatePromotable] is already
 * unconditionally `false` (see [se.blick.app.notification.PromotedNotificationChecker]), so
 * without this check the row would always render there with a settings link that can never
 * resolve, and the tap would silently do nothing.
 */
internal fun shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable: Boolean, sdkInt: Int): Boolean =
    !isLiveUpdatePromotable && sdkInt >= Build.VERSION_CODES.BAKLAVA

/**
 * Pulled out of [LiveUpdatePromotionRow] so the fallback path can be unit-tested without
 * Compose: [android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS] may still
 * not resolve even on Android 16+ (an OEM build that omits it — Android's own docs
 * acknowledge a matching activity isn't guaranteed to exist), and a tap that silently does
 * nothing is a real dead end for the user. Falls back to the ordinary per-app notification
 * settings screen ([notificationSettingsIntent], `Settings.ACTION_APP_NOTIFICATION_SETTINGS`),
 * which every supported Android version resolves — still notification-relevant, and strictly
 * better than no feedback at all. [startActivity] is injected (rather than calling
 * `context.startActivity` directly) purely so a test can observe/fail the first launch without
 * a real Android runtime resolving intents.
 */
internal fun launchLiveUpdateSettings(context: Context, startActivity: (Intent) -> Unit) {
    se.blick.app.ui.notification.launchLiveUpdateSettings(
        context = context,
        sdkInt = Build.VERSION_CODES.BAKLAVA,
        startActivity = startActivity,
    )
}

/**
 * Production hint shown only when base notification delivery
 * is already [NotificationAvailability.Available] but Live Update promotion specifically is
 * not — see [se.blick.app.notification.PromotedNotificationChecker]'s own doc for why "not
 * eligible" only ever means "currently not eligible," never "broken," since
 * [se.blick.app.notification.RoutineNotificationBuilder] already produces a perfectly valid
 * plain ongoing notification either way. Renders nothing when already eligible or below
 * Android 16 — see [shouldOfferLiveUpdateSettingsLink].
 *
 * Tapping the settings action goes through [launchLiveUpdateSettings], which falls back to
 * the ordinary notification settings screen rather than leaving the tap silently do nothing
 * on an OEM build without the Live Update settings screen.
 */
@Composable
private fun LiveUpdatePromotionRow(isLiveUpdatePromotable: Boolean) {
    if (!shouldOfferLiveUpdateSettingsLink(isLiveUpdatePromotable, Build.VERSION.SDK_INT)) return
    val context = LocalContext.current

    Column {
        Text(
            stringResource(R.string.routine_details_live_update_not_enabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        TextButton(
            onClick = { launchLiveUpdateSettings(context, context::startActivity) },
        ) {
            Text(stringResource(R.string.routine_details_live_update_settings_action))
        }
    }
}

@Composable
private fun JourneyTransportModesRow(
    selectedModes: Set<TransportMode>,
    isSaving: Boolean,
    updateFailed: Boolean,
    onSave: (Set<TransportMode>) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var draftModes by remember(selectedModes, showDialog) { mutableStateOf(selectedModes) }
    val selectedLabels = JOURNEY_TRANSPORT_MODE_OPTIONS
        .filter(selectedModes::contains)
        .map { mode -> stringResource(mode.detailsLabelResId()) }
        .joinToString(", ")

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.routine_details_mode_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selectedLabels, style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { showDialog = true },
                    enabled = !isSaving,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.routine_details_transport_change),
                    )
                }
            }
        }
        if (updateFailed) {
            Text(
                stringResource(R.string.routine_details_transport_update_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) showDialog = false },
            title = { Text(stringResource(R.string.routine_details_transport_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.routine_details_transport_dialog_body))
                    Spacer(Modifier.height(8.dp))
                    JOURNEY_TRANSPORT_MODE_OPTIONS.forEach { mode ->
                        val checked = mode in draftModes
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                draftModes = if (checked) draftModes - mode else draftModes + mode
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    draftModes = if (isChecked) draftModes + mode else draftModes - mode
                                },
                            )
                            Text(stringResource(mode.detailsLabelResId()))
                        }
                    }
                    if (draftModes.isEmpty()) {
                        Text(
                            stringResource(R.string.routine_details_transport_one_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(draftModes)
                        showDialog = false
                    },
                    enabled = draftModes.isNotEmpty() && !isSaving,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }, enabled = !isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** [dotColor] is null for every row except Status -- see that call site's own doc on why only
 * Enabled/Disabled (never Paused today) get one. Same small dot/gap/vertical-centering as the
 * departure list's own Live indicator (see [DepartureRow]), so both read as the same visual
 * language for "status" on this screen. */
@Composable
private fun DetailRow(label: String, value: String, dotColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            dotColor?.let { color ->
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Same label/value row layout as [DetailRow], except the value is the same colored
 * line-number badge used throughout the app rather than plain text — see
 * [se.blick.app.ui.components.LineBadge]'s own doc. */
@Composable
private fun LineDetailRow(label: String, lineDesignation: String, transportMode: TransportMode) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        LineBadge(lineDesignation = lineDesignation, transportMode = transportMode)
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
    transportMode: TransportMode,
    locale: java.util.Locale,
    onRefresh: () -> Unit,
) {
    when (state) {
        is LiveDeparturesState.Loading -> CenteredBox(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            CircularProgressIndicator()
        }
        is LiveDeparturesState.Live -> DeparturesList(state.snapshot.departures, transportMode, locale)
        is LiveDeparturesState.Stale -> Column {
            Text(
                stringResource(R.string.routine_details_stale_warning),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            DeparturesList(state.snapshot.departures, transportMode, locale)
        }
        is LiveDeparturesState.NoUpcomingDepartures -> RetryableMessage(R.string.routine_details_no_departures, onRefresh)
        is LiveDeparturesState.Offline -> RetryableMessage(R.string.routine_details_offline, onRefresh)
        is LiveDeparturesState.Unavailable -> RetryableMessage(R.string.routine_details_unavailable, onRefresh)
    }
}

/**
 * Dedicated disruptions section for one routine's site/line/mode (see [DisruptionsState] and
 * [se.blick.app.domain.usecase.GetDisruptionsUseCase]) — loading and unavailable are each their
 * own clear, distinct message, matching [DeparturesSection]'s own per-state convention.
 * [DisruptionsState.NoDisruptions] renders nothing here -- the caller ([RoutineDetailsContent])
 * skips this whole section, heading included, before ever reaching this composable in that
 * state; the branch below only exists because a sealed [DisruptionsState] `when` must stay
 * exhaustive. Entries are rendered in the order [se.blick.app.domain.model.relevantDisruptions]
 * already sorted them in (highest priority first) — no re-sorting here.
 */
@Composable
private fun DisruptionsSection(state: DisruptionsState) {
    when (state) {
        is DisruptionsState.Loading -> CenteredBox(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            CircularProgressIndicator()
        }
        is DisruptionsState.Loaded -> DisruptionsList(state.disruptions.map { it.toPresentation() })
        is DisruptionsState.NoDisruptions -> Unit
        is DisruptionsState.Unavailable -> Text(
            stringResource(R.string.routine_details_disruptions_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Shared by both routine types — a `LINE_DIRECTION` [Disruption] adapted via
 * [se.blick.app.domain.model.toPresentation], or an `EXACT_DESTINATION` PRIMARY journey's own
 * [se.blick.app.domain.model.ResolvedJourneyDisruption]s, mapped 1:1 via that same extension —
 * see [DisruptionPresentation]'s own doc. Multiple genuinely different notices are always all
 * shown here, one card each; only the notification/widget's single compact indicator ever
 * collapses them (see [se.blick.app.domain.usecase.compactPresentation]). */
@Composable
private fun DisruptionsList(disruptions: List<DisruptionPresentation>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        disruptions.forEach { disruption -> DisruptionRow(disruption) }
    }
}

/**
 * One disruption as a muted-red card — [MaterialTheme.colorScheme.errorContainer] (a
 * low-opacity, theme-derived red tint that Material3 already keeps readable against
 * [MaterialTheme.colorScheme.onErrorContainer] text in both light and dark mode, rather than a
 * hand-picked alpha over the bright [MaterialTheme.colorScheme.error] red used for genuine
 * failure states elsewhere on this screen) so a disruption is clearly noticeable without being
 * visually harsh. Always shows [DisruptionPresentation]'s own real, unaltered text — never only
 * the classified [DisruptionPresentation.effect] label (that compact summary is the
 * notification/widget's own job, not this detailed screen's) — this holds for a `CONFIRMED`
 * exact-destination disruption AND a `LINE_RELEVANT` one alike: the real SL header/details are
 * never hidden merely because confidence is only line-level (see
 * [se.blick.app.domain.model.DisruptionRelevance]'s own doc). Collapsed by default, showing only
 * [DisruptionPresentation.headline]; the expand/collapse icon button reveals
 * [DisruptionPresentation.details] below it when one exists, mirroring the same collapsed-header/
 * expanded-details split the notification's own [se.blick.app.notification.RoutineNotificationBuilder]
 * uses. A `LINE_DIRECTION`/`CONFIRMED` disruption's own [DisruptionPresentation.uncertainLineDesignations]
 * is always empty; when it is NOT empty (a `LINE_RELEVANT` exact-destination disruption), an
 * additional small caption makes clear this card's own real text has NOT been proven to affect
 * this exact journey's own segment — the real text stays visible, never replaced, but is no
 * longer presented as though it were confirmed. A Journey Planner notice has no separate details
 * (it is already one short piece of text, unlike an SL Deviations message's own header/details
 * split), so the expand affordance itself is omitted rather than expanding to nothing.
 */
@Composable
private fun DisruptionRow(disruption: DisruptionPresentation) {
    var expanded by remember(disruption.headline) { mutableStateOf(false) }
    val details = disruption.details

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠️ ${disruption.headline}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (details != null) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (expanded) R.string.routine_details_disruption_collapse else R.string.routine_details_disruption_expand,
                            ),
                        )
                    }
                }
            }
            if (disruption.uncertainLineDesignations.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.routine_details_disruption_line_relevant_qualifier,
                        disruption.uncertainLineDesignations.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded && details != null) {
                Spacer(Modifier.height(4.dp))
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
        }
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
private fun DeparturesList(departures: List<PreparedDeparture>, transportMode: TransportMode, locale: java.util.Locale) {
    Column {
        departures.forEach { departure ->
            DepartureRow(departure, transportMode, locale)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DepartureRow(departure: PreparedDeparture, transportMode: TransportMode, locale: java.util.Locale) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(lineDesignation = departure.lineDesignation, transportMode = transportMode)
                Spacer(Modifier.width(8.dp))
                Text(
                    departure.destination ?: stringResource(R.string.direction_unknown_destination),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only the "Live" label gets the dot -- "Cancelled"/"Scheduled" (the other two
                // departureStatusLabel outcomes) are left exactly as before. Reuses
                // LINE_BADGE_GREEN, the same green BlickRoutineWidget's own status dot already
                // uses for this identical real-time/non-cancelled condition, so the two
                // surfaces agree on what "live" looks like.
                if (departure.isRealTime && !departure.isCancelled) {
                    Box(Modifier.size(6.dp).background(LINE_BADGE_GREEN, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    departureStatusLabel(departure),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (departure.isCancelled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                )
            }
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

private fun TransportMode.journeyLabelResId(): Int = when (this) {
    TransportMode.METRO -> R.string.journey_mode_metro
    TransportMode.TRAIN -> R.string.journey_mode_commuter_rail
    TransportMode.BUS -> R.string.journey_mode_bus
    TransportMode.TRAM -> R.string.journey_mode_tram
    TransportMode.SHIP, TransportMode.FERRY -> R.string.journey_mode_ferry
    TransportMode.TAXI, TransportMode.UNKNOWN -> R.string.transport_mode_unknown
}
