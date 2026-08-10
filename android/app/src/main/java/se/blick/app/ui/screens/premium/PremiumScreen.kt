package se.blick.app.ui.screens.premium

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.R
import se.blick.app.billing.EntitlementState
import se.blick.app.ui.components.BlickTopBar

@Composable
fun PremiumScreen(onBack: () -> Unit, viewModel: PremiumViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    Scaffold(topBar = { BlickTopBar(title = stringResource(R.string.premium_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.premium_heading), style = MaterialTheme.typography.headlineMedium)
            Text("• ${stringResource(R.string.premium_feature_multiple)}")
            Text("• ${stringResource(R.string.premium_feature_destinations)}")
            Text("• ${stringResource(R.string.premium_feature_fastest)}")
            Text("• ${stringResource(R.string.premium_feature_once)}")
            Spacer(Modifier.height(8.dp))
            when (state.entitlement) {
                EntitlementState.Premium -> Text(stringResource(
                    if (state.debugOverrideEnabled) R.string.premium_debug_active else R.string.premium_active,
                ))
                EntitlementState.Pending -> Text(stringResource(R.string.premium_pending))
                is EntitlementState.TemporarilyUnavailable -> Text(stringResource(R.string.premium_unavailable))
                else -> Unit
            }
            Button(
                enabled = activity != null && state.entitlement !is EntitlementState.Premium,
                onClick = { activity?.let(viewModel::launchPurchase) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(state.localizedPrice?.let { stringResource(R.string.premium_purchase_with_price, it) }
                    ?: stringResource(R.string.premium_purchase))
            }
            OutlinedButton(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.premium_restore))
            }
            if (state.debugOverrideAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.premium_debug_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedButton(onClick = viewModel::toggleDebugPremium, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(
                        if (state.debugOverrideEnabled) R.string.premium_debug_disable else R.string.premium_debug_enable,
                    ))
                }
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
