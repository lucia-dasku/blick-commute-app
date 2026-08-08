@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.about

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import se.blick.app.BuildConfig
import se.blick.app.R
import se.blick.app.ui.components.BlickTopBar

/** The Trafiklab.se link this screen opens — see docs/api-contract.md §9 (Licensing and
 * attribution): "the attribution should link to Trafiklab.se where practicable." */
private const val TRAFIKLAB_URL = "https://www.trafiklab.se/"

/** The Blick Labs webpage this screen's "Open-source licences" section links to. */
private const val OPEN_SOURCE_LICENSES_URL = "https://blick-labs.vercel.app/blick-privacy"

/**
 * The one place [R.string.attribution_text] and the privacy policy are actually shown to the
 * user (see docs/api-contract.md §9) — reachable from an info action in
 * [se.blick.app.ui.screens.routinelist.RoutineListScreen]'s top app bar. Deliberately simple:
 * a tagline, the app's own version, a "Data and attribution" section (attribution text, a link
 * to Trafiklab.se, and an explicit non-affiliation disclaimer), the full privacy policy, an
 * "Open-source licences" section linking to the Blick Labs website ([OPEN_SOURCE_LICENSES_URL]),
 * and finally a centered copyright line as the very last thing on the screen — no settings are
 * hosted here yet, despite the name matching the product doc's "About/Settings screen" wording.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            BlickTopBar(title = stringResource(R.string.about_title), onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.about_section_data_attribution), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.attribution_text), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, TRAFIKLAB_URL.toUri()))
                },
            ) {
                Text(stringResource(R.string.about_trafiklab_link))
            }
            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.about_section_privacy_policy), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_privacy_last_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_operator), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_no_account), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_local_storage), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_backend), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_usage), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_contact), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_privacy_updates), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.about_section_open_source_licences), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_open_source_licences_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, OPEN_SOURCE_LICENSES_URL.toUri()))
                },
            ) {
                Text(stringResource(R.string.about_open_source_licences_action))
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
