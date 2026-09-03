package se.blick.app.ui.screens.premium

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.theme.themedScreenContainerColor

@Composable
fun PremiumScreen(onBack: () -> Unit, viewModel: PremiumViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = { BlickTopBar(title = stringResource(R.string.premium_title), onBack = onBack) },
    ) { padding ->
        PremiumContent(
            state = state,
            canLaunchPurchase = activity != null,
            onPurchase = { activity?.let(viewModel::launchPurchase) },
            onRestore = viewModel::restore,
            onToggleDebugPremium = viewModel::toggleDebugPremium,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
internal fun PremiumContent(
    state: PremiumUiState,
    canLaunchPurchase: Boolean,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onToggleDebugPremium: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.premium_heading), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.premium_supporting_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PremiumBenefit(stringResource(R.string.premium_feature_multiple))
            PremiumBenefit(stringResource(R.string.premium_feature_destinations))
            PremiumBenefit(stringResource(R.string.premium_feature_one_time_event))
            PremiumBenefit(stringResource(R.string.premium_feature_event_recommendations))
            PremiumBenefit(stringResource(R.string.premium_feature_stockholm_night))
            PremiumBenefit(stringResource(R.string.premium_feature_ad_free))
        }
        when (state.entitlement) {
            EntitlementState.Premium -> PremiumActiveStatus(state.debugOverrideEnabled)
            EntitlementState.Pending -> Text(stringResource(R.string.premium_pending))
            is EntitlementState.TemporarilyUnavailable -> Text(stringResource(R.string.premium_unavailable))
            else -> Unit
        }
        Text(
            text = stringResource(R.string.premium_purchase_terms),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Button(
            enabled = canLaunchPurchase && state.entitlement !is EntitlementState.Premium,
            onClick = onPurchase,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.localizedPrice?.let {
                    stringResource(R.string.premium_purchase_with_price, it)
                } ?: stringResource(R.string.premium_purchase),
            )
        }
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.premium_restore))
        }
        PremiumDeveloperOverrideControls(
            available = state.debugOverrideAvailable,
            enabled = state.debugOverrideEnabled,
            onToggle = onToggleDebugPremium,
        )
    }
}

@Composable
private fun PremiumBenefit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).size(16.dp).testTag("premium-benefit-check"),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
