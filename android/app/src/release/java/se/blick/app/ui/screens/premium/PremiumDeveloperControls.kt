package se.blick.app.ui.screens.premium

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.blick.app.R

@Composable
internal fun PremiumActiveStatus(debugOverrideEnabled: Boolean) {
    Text(stringResource(R.string.premium_active))
}

@Composable
internal fun PremiumDeveloperOverrideControls(
    available: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) = Unit
