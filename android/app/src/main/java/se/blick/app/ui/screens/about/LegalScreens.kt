package se.blick.app.ui.screens.about

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import se.blick.app.R
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.theme.themedScreenContainerColor

private const val TRAFIKLAB_URL = "https://www.trafiklab.se/"
internal const val PRIVACY_POLICY_URL = "https://blick-labs.vercel.app/blick-privacy"
internal const val OPEN_SOURCE_LICENCES_URL = "https://blick-labs.vercel.app/licenses.html"
internal const val PRIVACY_POLICY_LINK_TAG = "privacy-policy-link"

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LegalContent(title = stringResource(R.string.about_section_privacy_policy), onBack = onBack) {
        Text(
            stringResource(R.string.about_privacy_last_updated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        LegalParagraph(R.string.about_privacy_operator)
        LegalParagraph(R.string.about_privacy_no_account)
        LegalParagraph(R.string.about_privacy_local_storage)
        LegalParagraph(R.string.about_privacy_backend)
        LegalParagraph(R.string.about_privacy_advertising)
        LegalParagraph(R.string.about_privacy_usage)
        LegalParagraph(R.string.about_privacy_contact)
        LegalParagraph(R.string.about_privacy_updates)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_privacy_read_more), style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) },
            modifier = Modifier.testTag(PRIVACY_POLICY_LINK_TAG),
        ) {
            Text(stringResource(R.string.about_section_privacy_policy))
        }
    }
}

@Composable
fun DataAttributionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LegalContent(title = stringResource(R.string.about_section_data_attribution), onBack = onBack) {
        Text(stringResource(R.string.attribution_text), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, TRAFIKLAB_URL.toUri())) }) {
            Text(stringResource(R.string.about_trafiklab_link))
        }
        Text(
            stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
fun OpenSourceLicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LegalContent(title = stringResource(R.string.about_section_open_source_licences), onBack = onBack) {
        Text(stringResource(R.string.about_open_source_licences_body), style = MaterialTheme.typography.bodyLarge)
        TextButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, OPEN_SOURCE_LICENCES_URL.toUri())) },
        ) {
            Text(stringResource(R.string.about_open_source_licences_action))
        }
    }
}

@Composable
private fun LegalContent(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = { BlickTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun LegalParagraph(stringRes: Int) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(stringRes), style = MaterialTheme.typography.bodyMedium)
}
