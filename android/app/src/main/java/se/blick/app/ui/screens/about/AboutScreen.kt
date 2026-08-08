@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.about

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.hilt.navigation.compose.hiltViewModel
import se.blick.app.BuildConfig
import se.blick.app.R
import se.blick.app.locale.currentBlickLocale
import se.blick.app.ui.components.BlickTopBar

/** The Trafiklab.se link this screen opens — see docs/api-contract.md §9 (Licensing and
 * attribution): "the attribution should link to Trafiklab.se where practicable." */
private const val TRAFIKLAB_URL = "https://www.trafiklab.se/"

/** The Blick Labs webpage this screen's "Open-source licences" section links to. */
private const val OPEN_SOURCE_LICENSES_URL = "https://blick-labs.vercel.app/blick-privacy"

/**
 * Settings/About: Blick's own language picker first, then the one place
 * [R.string.attribution_text] and the privacy policy are actually shown to the user (see
 * docs/api-contract.md §9) — reachable from the settings action in
 * [se.blick.app.ui.screens.routinelist.RoutineListScreen]'s top app bar. Deliberately simple: a
 * "Language" section (English/Svenska), the app's own tagline and version, a "Data and
 * attribution" section (attribution text, a link to Trafiklab.se, and an explicit
 * non-affiliation disclaimer), the full privacy policy, an "Open-source licences" section
 * linking to the Blick Labs website ([OPEN_SOURCE_LICENSES_URL]), and finally a centered
 * copyright line as the very last thing on the screen.
 *
 * Thin [AboutViewModel]-resolving wrapper around [AboutContent] — pulled apart exactly like
 * [se.blick.app.ui.screens.routinelist.RoutineListScreen]/`RoutineListContent`, so
 * `AboutScreenTest` can keep exercising the real UI directly with a plain lambda, no
 * Hilt test rule needed.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    AboutContent(onBack = onBack, onLanguageSelected = viewModel::onLanguageSelected)
}

/** Stateless — see [AboutScreen]'s own doc on why this split exists. */
@Composable
internal fun AboutContent(onBack: () -> Unit, onLanguageSelected: (String) -> Unit) {
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
            LanguageSection(onLanguageSelected = onLanguageSelected)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

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

/**
 * Blick's own explicit English/Svenska choice — exactly two options, per the product decision
 * to not surface a third "system default" choice in this UI (see [AboutViewModel]'s own doc on
 * why [androidx.appcompat.app.AppCompatDelegate] is the actual source of truth, not any state
 * owned here). [currentLanguage] runs [currentBlickLocale] — the same effective-locale rule
 * [se.blick.app.ui.screens.routinecreate.RoutineCreateScreen]/
 * [se.blick.app.ui.screens.routinedetails.RoutineDetailsScreen] use for weekday/time formatting
 * — rather than a second, separately-tracked "which language is selected" flag: that normalizes
 * an explicit choice or, absent one, the device's ordered system locale list (falling back to
 * English for an unsupported one, e.g. Lithuanian) down to whichever of Blick's two languages is
 * actually showing, so the selected chip is never out of sync with — and never silently agrees
 * with neither of — what the rest of this screen's own text is actually rendered in.
 * [FilterChip], the same selectable-option control [WeekdaySelector][se.blick.app.ui.screens.routinecreate.WeekdaySelector]
 * already uses elsewhere in this app, rather than a new selection control.
 */
@Composable
private fun LanguageSection(onLanguageSelected: (String) -> Unit) {
    val currentLanguage = currentBlickLocale().language
    Text(stringResource(R.string.settings_language_label), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = currentLanguage == "en",
            onClick = { onLanguageSelected("en") },
            label = { Text(stringResource(R.string.settings_language_option_english)) },
        )
        FilterChip(
            selected = currentLanguage == "sv",
            onClick = { onLanguageSelected("sv") },
            label = { Text(stringResource(R.string.settings_language_option_swedish)) },
        )
    }
}
