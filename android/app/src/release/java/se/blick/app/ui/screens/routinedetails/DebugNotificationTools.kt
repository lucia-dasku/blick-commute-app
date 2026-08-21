package se.blick.app.ui.screens.routinedetails

import androidx.compose.runtime.Composable
import se.blick.app.domain.model.DisruptionEffect
import se.blick.app.notification.NotificationPostResult

internal const val DEBUG_NOTIFICATION_TOOLS_AVAILABLE = false

@Composable
internal fun DebugNotificationToolsContent(
    canShow: Boolean,
    onShow: () -> NotificationPostResult?,
    onShowForEffect: (DisruptionEffect) -> NotificationPostResult?,
    onRemove: () -> Unit,
    isLiveUpdatePromotable: () -> Boolean,
) = Unit
