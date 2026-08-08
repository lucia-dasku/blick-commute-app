@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.routinedetails

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import se.blick.app.BuildConfig
import se.blick.app.R
import se.blick.app.domain.model.CommuteRoutine
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.usecase.DisruptionsState
import se.blick.app.domain.usecase.LiveDeparturesState
import se.blick.app.domain.usecase.PreparedDeparture
import se.blick.app.domain.model.TransportMode
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationPostResult
import se.blick.app.ui.components.LineBadge
import se.blick.app.ui.notification.notificationSettingsIntent
import se.blick.app.ui.notification.promotedNotificationSettingsIntent
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
                disruptionsState = uiState.disruptions,
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
                onShowDebugNotification = viewModel::showDebugTestNotification,
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
    onRemoveDebugNotification: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) {
    val locale = LocalLocale.current.platformLocale

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Shown first, above everything else including the routine's own name -- a relevant
        // disruption is the whole reason someone taps the notification or widget to "see more"
        // (see RoutineNotificationBuilder.contentIntent / RoutineWidgetTapIntent, which both
        // land here), so it must be the first thing visible without any scrolling, not buried
        // below routine details/actions/departures. Skipped entirely once a fetch has actually
        // completed and found nothing relevant: a "Disruptions" heading over an empty/"none"
        // message is noise once that's confirmed, not useful signal. Loading and Unavailable are
        // each still shown -- neither one means "no disruptions", just "don't know yet" /
        // "couldn't check".
        if (disruptionsState !is DisruptionsState.NoDisruptions) {
            Text(stringResource(R.string.routine_details_disruptions_heading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            DisruptionsSection(disruptionsState)
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

        DeparturesSection(departuresState, routine.transportMode, locale, onRefresh)

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

        // routine.name's own default pattern is "{siteName} → {destination}" (see
        // RoutineCreateViewModel.selectDirection) -- a separate site-name line here would
        // just repeat it a second time.
        Text(routine.name, style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(12.dp))
        DetailRow(stringResource(R.string.routine_details_mode_label), stringResource(routine.transportMode.detailsLabelResId()))
        routine.lineDesignation?.let { designation ->
            LineDetailRow(stringResource(R.string.routine_details_line_label), designation, routine.transportMode)
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

        // Debug-only manual notification trigger (Part 6 of the ongoing-notification
        // foundation milestone) — see RoutineDetailsViewModel.showDebugTestNotification's
        // doc comment. BuildConfig.DEBUG is compile-time-constant, so R8 strips this whole
        // block (and the section below) out of a release build entirely.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            DebugNotificationSection(
                canShow = departuresState !is LiveDeparturesState.Loading,
                onShow = onShowDebugNotification,
                onRemove = onRemoveDebugNotification,
                isLiveUpdatePromotable = isLiveUpdatePromotable,
            )
        }
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
    onRemove: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) {
    val context = LocalContext.current
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // Resolved here, in composable scope, rather than via context.getString(...) inside the
    // callbacks below -- LocalContext.current reads don't get invalidated on a Configuration
    // change, so a getString() call made lazily inside a lambda can return a stale value;
    // stringResource() here is recomposed correctly and the resolved String is then just
    // captured by the lambdas like any other value.
    val permissionDeniedMessage = stringResource(R.string.debug_notification_permission_denied)
    val removedMessage = stringResource(R.string.debug_notification_removed)
    val promotedSuffix = stringResource(R.string.debug_notification_promoted_suffix)
    val notPromotedSuffix = stringResource(R.string.debug_notification_not_promoted_suffix)

    fun messageFor(result: NotificationPostResult?): String {
        val base = result.toDebugMessage(context)
        if (result !is NotificationPostResult.Posted) return base
        return base + if (isLiveUpdatePromotable()) promotedSuffix else notPromotedSuffix
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resultMessage = if (granted) {
            messageFor(onShow())
        } else {
            permissionDeniedMessage
        }
    }

    Column {
        Text(stringResource(R.string.debug_notification_section_heading), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val needsPermissionRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermissionRequest) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    resultMessage = messageFor(onShow())
                }
            },
            enabled = canShow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.debug_show_test_notification))
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                onRemove()
                resultMessage = removedMessage
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.debug_remove_test_notification))
        }

        resultMessage?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/**
 * Maps the notifier's real [NotificationPostResult] to user-facing debug text — the only
 * place in [DebugNotificationSection] allowed to produce the "posted" message, and only for
 * [NotificationPostResult.Posted]. A null result (no routine loaded — see
 * [RoutineDetailsViewModel.showDebugTestNotification]) falls back to the same generic
 * failure wording as [NotificationPostResult.Failed]: this branch should be unreachable in
 * practice since [DebugNotificationSection]'s "Show" button is disabled until a routine is
 * loaded, but it must never silently claim success either way.
 */
internal fun NotificationPostResult?.toDebugMessage(context: android.content.Context): String = when (this) {
    NotificationPostResult.Posted -> context.getString(R.string.debug_notification_posted)
    NotificationPostResult.NotificationsDisabled -> context.getString(R.string.debug_notification_disabled)
    NotificationPostResult.Failed, null -> context.getString(R.string.debug_notification_failed)
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
        OutlinedButton(
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
 * Every action inside, once expanded, keeps its exact pre-existing behaviour, confirmation
 * dialog (delete, handled by the caller via [onRequestDelete]), and styling -- this composable
 * only changes what's visible before the user taps to expand it, never what any individual
 * action itself does.
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

            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.routine_details_edit_action))
            }
            Spacer(Modifier.height(8.dp))

            // Never colour-only: the label itself always states the resulting/current state in
            // words (see the milestone requirement on text scaling + no colour-only status).
            OutlinedButton(
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
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
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
    try {
        startActivity(promotedNotificationSettingsIntent(context))
    } catch (e: ActivityNotFoundException) {
        startActivity(notificationSettingsIntent(context))
    }
}

/**
 * Production (not [BuildConfig.DEBUG]-gated) hint shown only when base notification delivery
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
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
        is DisruptionsState.Loaded -> DisruptionsList(state.disruptions)
        is DisruptionsState.NoDisruptions -> Unit
        is DisruptionsState.Unavailable -> Text(
            stringResource(R.string.routine_details_disruptions_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DisruptionsList(disruptions: List<Disruption>) {
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
 * visually harsh. Collapsed by default, showing only [se.blick.app.domain.model.DisruptionMessage.header];
 * the expand/collapse icon button reveals [se.blick.app.domain.model.DisruptionMessage.details]
 * below it, mirroring the same collapsed-header/expanded-details split the notification's own
 * [se.blick.app.notification.RoutineNotificationBuilder] uses.
 */
@Composable
private fun DisruptionRow(disruption: Disruption) {
    var expanded by remember(disruption.disruptionId) { mutableStateOf(false) }

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
                Text("⚠️ ${disruption.message.header}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.routine_details_disruption_collapse else R.string.routine_details_disruption_expand,
                        ),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(disruption.message.details, style = MaterialTheme.typography.bodySmall)
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
