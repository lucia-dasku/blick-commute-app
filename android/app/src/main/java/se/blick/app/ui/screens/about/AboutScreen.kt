@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.about

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import se.blick.app.BuildConfig
import se.blick.app.R

/** The Trafiklab.se link this screen opens — see docs/api-contract.md §9 (Licensing and
 * attribution): "the attribution should link to Trafiklab.se where practicable." */
private const val TRAFIKLAB_URL = "https://www.trafiklab.se/"

/**
 * The one place [R.string.attribution_text] is actually shown to the user (see
 * docs/api-contract.md §9) — reachable from an info action in [se.blick.app.ui.screens.routinelist.RoutineListScreen]'s
 * top app bar. Deliberately simple: attribution text, a link to Trafiklab.se, an explicit
 * non-affiliation disclaimer, and the app's own version — no settings are hosted here yet,
 * despite the name matching the product doc's "About/Settings screen" wording.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.attribution_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, TRAFIKLAB_URL.toUri()))
                },
            ) {
                Text(stringResource(R.string.about_trafiklab_link))
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
