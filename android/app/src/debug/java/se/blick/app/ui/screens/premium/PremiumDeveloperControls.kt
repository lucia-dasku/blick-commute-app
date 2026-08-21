package se.blick.app.ui.screens.premium

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.blick.app.R

@Composable
internal fun PremiumActiveStatus(debugOverrideEnabled: Boolean) {
    Text(stringResource(if (debugOverrideEnabled) R.string.premium_debug_active else R.string.premium_active))
}

@Composable
internal fun PremiumDeveloperOverrideControls(
    available: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    if (!available) return
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.premium_debug_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (enabled) R.string.premium_debug_disable else R.string.premium_debug_enable))
    }
}
