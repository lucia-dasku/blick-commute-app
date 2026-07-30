package se.blick.app.ui.notification

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import se.blick.app.R

/**
 * The production notification-permission flow (see the product doc's "Production notification
 * permission" requirement) as a small, reusable, stateless-from-the-caller's-perspective piece
 * of Compose UI: asks for `POST_NOTIFICATIONS` at an appropriate user-driven moment — saving or
 * enabling a routine intended to show notifications — with a brief rationale dialog first, and
 * never asks again afterward regardless of the user's answer.
 *
 * Deliberately separate from [se.blick.app.ui.screens.routinedetails.DebugNotificationSection]'s
 * own independent, debug-only permission request — that one exists purely to exercise the
 * notifier itself before this production flow existed, and intentionally does not touch
 * [hasSeenRationale]/[onRationaleSeen] (see that section's own doc comment).
 *
 * [hasSeenRationale] and [onRationaleSeen] are supplied by the caller's own ViewModel (backed
 * by `AppSettingsDataStore.hasSeenNotificationRationale`, the single source of truth for
 * "already asked once") rather than this composable owning that state itself.
 *
 * @return a function to wrap the original action with: `gate { doTheThing() }` shows the
 * rationale dialog and requests the permission first if this is the very first time (per
 * [hasSeenRationale]), then always calls through to the wrapped action afterward regardless of
 * whether permission was actually granted — the permission is optional/best-effort for
 * automatic notifications, never a hard gate on the underlying routine action (saving or
 * enabling) itself. On API < 33 (no runtime permission to request) or once
 * [hasSeenRationale] is already true, the action runs immediately with no dialog.
 */
@Composable
fun rememberNotificationPermissionGate(
    hasSeenRationale: Boolean,
    onRationaleSeen: () -> Unit,
): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showRationale by remember { mutableStateOf(false) }

    fun finishAndRun() {
        onRationaleSeen()
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> finishAndRun() } // Granted or denied, the flow ends the same way -- see class doc.

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                finishAndRun()
            },
            title = { Text(stringResource(R.string.notification_permission_rationale_title)) },
            text = { Text(stringResource(R.string.notification_permission_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text(stringResource(R.string.notification_permission_rationale_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    finishAndRun()
                }) {
                    Text(stringResource(R.string.notification_permission_rationale_not_now))
                }
            },
        )
    }

    return { action ->
        val needsPermissionRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermissionRequest && !hasSeenRationale) {
            pendingAction = action
            showRationale = true
        } else {
            action()
        }
    }
}
