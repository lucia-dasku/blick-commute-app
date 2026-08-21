package se.blick.app.ui.screens.routinedetails

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import se.blick.app.R
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.notification.NotificationPostResult
import se.blick.app.notification.disruptionEffectLabelRes

internal const val DEBUG_NOTIFICATION_TOOLS_AVAILABLE = true

@Composable
internal fun DebugNotificationToolsContent(
    canShow: Boolean,
    onShow: () -> NotificationPostResult?,
    onShowForEffect: (DisruptionEffect) -> NotificationPostResult?,
    onRemove: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) {
    val context = LocalContext.current
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var selectedEffect by remember { mutableStateOf<DisruptionEffect?>(null) }
    val permissionDeniedMessage = stringResource(R.string.debug_notification_permission_denied)
    val removedMessage = stringResource(R.string.debug_notification_removed)
    val promotedSuffix = stringResource(R.string.debug_notification_promoted_suffix)
    val notPromotedSuffix = stringResource(R.string.debug_notification_not_promoted_suffix)
    val realDisruptionLabel = stringResource(R.string.debug_disruption_effect_real)

    fun messageFor(result: NotificationPostResult?): String {
        val base = result.toDebugMessage(context)
        if (result !is NotificationPostResult.Posted) return base
        return base + if (isLiveUpdatePromotable()) promotedSuffix else notPromotedSuffix
    }

    fun showForCurrentSelection(): NotificationPostResult? = selectedEffect?.let(onShowForEffect) ?: onShow()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resultMessage = if (granted) messageFor(showForCurrentSelection()) else permissionDeniedMessage
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Column {
        Text(stringResource(R.string.debug_notification_section_heading), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.debug_disruption_effect_picker_label), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedEffect == null,
                onClick = { selectedEffect = null },
                label = { Text(realDisruptionLabel) },
            )
            DisruptionEffect.entries.forEach { effect ->
                FilterChip(
                    selected = selectedEffect == effect,
                    onClick = { selectedEffect = effect },
                    label = { Text(stringResource(disruptionEffectLabelRes(effect))) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val needsPermissionRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermissionRequest) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    resultMessage = messageFor(showForCurrentSelection())
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

internal fun NotificationPostResult?.toDebugMessage(context: android.content.Context): String = when (this) {
    NotificationPostResult.Posted -> context.getString(R.string.debug_notification_posted)
    NotificationPostResult.NotificationsDisabled -> context.getString(R.string.debug_notification_disabled)
    NotificationPostResult.Failed, null -> context.getString(R.string.debug_notification_failed)
}
