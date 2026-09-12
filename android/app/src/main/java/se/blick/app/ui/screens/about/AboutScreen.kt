@file:OptIn(ExperimentalMaterial3Api::class)

package se.blick.app.ui.screens.about

import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.blick.app.BuildConfig
import se.blick.app.R
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.locale.currentBlickLocale
import se.blick.app.notification.NotificationAvailability
import se.blick.app.ui.components.BlickTopBar
import se.blick.app.ui.notification.launchLiveUpdateSettings
import se.blick.app.ui.notification.notificationSettingsIntent
import se.blick.app.ui.theme.AppearanceMode
import se.blick.app.ui.theme.LocalLightCityTheme
import se.blick.app.ui.theme.LocalStockholmNightTheme
import se.blick.app.ui.theme.themedScreenContainerColor

private const val SUPPORT_EMAIL = "contactblicklabs@gmail.com"
internal const val LANGUAGE_OPTION_EN_TAG = "settings-language-en"
internal const val LANGUAGE_OPTION_SV_TAG = "settings-language-sv"
internal const val PRIVACY_CHOICES_TAG = "settings-privacy-choices"
internal const val REVIEWER_ACCESS_ROW_TAG = "settings-reviewer-access"
internal const val REVIEWER_ACCESS_CODE_TAG = "settings-reviewer-access-code"
internal const val REVIEWER_ACCESS_ACTIVATE_TAG = "settings-reviewer-access-activate"
internal const val REVIEWER_ACCESS_DEACTIVATE_TAG = "settings-reviewer-access-deactivate"
internal const val REVIEWER_ACCESS_PROGRESS_TAG = "settings-reviewer-access-progress"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPremium: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenDataAttribution: () -> Unit,
    onOpenOpenSourceLicences: () -> Unit,
    privacyOptionsRequired: Boolean,
    onOpenPrivacyOptions: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val supportSubject = stringResource(R.string.settings_support_email_subject)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AboutContent(
        state = state,
        onBack = onBack,
        onLanguageSelected = viewModel::onLanguageSelected,
        onAppearanceSelected = viewModel::onAppearanceSelected,
        onOpenNotifications = { context.startActivity(notificationSettingsIntent(context)) },
        onOpenLiveUpdates = { launchLiveUpdateSettings(context) },
        onOpenPremium = onOpenPremium,
        onContactSupport = { context.startActivity(supportEmailIntent(supportSubject)) },
        onOpenPrivacyPolicy = onOpenPrivacyPolicy,
        onOpenDataAttribution = onOpenDataAttribution,
        onOpenOpenSourceLicences = onOpenOpenSourceLicences,
        privacyOptionsRequired = privacyOptionsRequired,
        onOpenPrivacyOptions = onOpenPrivacyOptions,
        onActivateReviewerAccess = viewModel::activateReviewerAccess,
        onDeactivateReviewerAccess = viewModel::deactivateReviewerAccess,
        onClearReviewerAccessOperation = viewModel::clearReviewerAccessOperation,
    )
}

internal fun supportEmailIntent(subject: String): Intent = Intent(
    Intent.ACTION_SENDTO,
    "mailto:$SUPPORT_EMAIL".toUri().buildUpon().appendQueryParameter("subject", subject).build(),
)

@Composable
internal fun AboutContent(
    state: AboutUiState = AboutUiState(),
    sdkInt: Int = Build.VERSION.SDK_INT,
    onBack: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onAppearanceSelected: (AppearanceMode) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenLiveUpdates: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onContactSupport: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenDataAttribution: () -> Unit = {},
    onOpenOpenSourceLicences: () -> Unit = {},
    privacyOptionsRequired: Boolean = false,
    onOpenPrivacyOptions: () -> Unit = {},
    onActivateReviewerAccess: (String) -> Unit = {},
    onDeactivateReviewerAccess: () -> Unit = {},
    onClearReviewerAccessOperation: () -> Unit = {},
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showReviewerAccessDialog by remember { mutableStateOf(false) }
    val currentLanguage = currentBlickLocale().language
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = themedScreenContainerColor(),
        topBar = { BlickTopBar(title = stringResource(R.string.about_title), onBack = onBack) },
    ) { padding ->
        // Transparent headers need the scroll viewport to stay below the top bar and its insets.
        val scrollViewport = if (LocalLightCityTheme.current || LocalStockholmNightTheme.current) {
            Modifier.padding(padding).verticalScroll(scrollState)
        } else {
            Modifier.verticalScroll(scrollState).padding(padding)
        }
        Column(
            Modifier
                .fillMaxSize()
                .then(scrollViewport)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SettingsSectionTitle(R.string.settings_section_preferences)
            SettingsRow(
                label = stringResource(R.string.settings_language_label),
                value = stringResource(
                    if (currentLanguage == "sv") R.string.settings_language_option_swedish
                    else R.string.settings_language_option_english,
                ),
                onClick = { showLanguageDialog = true },
            )
            SettingsRow(
                label = stringResource(R.string.settings_appearance_label),
                value = stringResource(appearanceLabelRes(state.appearanceMode)),
                onClick = { showAppearanceDialog = true },
            )

            SettingsSectionTitle(R.string.settings_section_commute)
            SettingsRow(
                label = stringResource(R.string.settings_notifications_label),
                value = stringResource(notificationLabelRes(state.notificationAvailability)),
                onClick = onOpenNotifications,
            )
            SettingsRow(
                label = stringResource(R.string.settings_live_updates_label),
                value = stringResource(liveUpdatesLabelRes(state.liveUpdatesEnabled, sdkInt)),
                onClick = onOpenLiveUpdates,
            )

            SettingsSectionTitle(R.string.settings_section_premium)
            SettingsRow(
                label = stringResource(R.string.settings_premium_label),
                value = stringResource(
                    if (state.entitlement.hasPremiumAccess) R.string.settings_premium_status_premium
                    else R.string.settings_premium_status_free,
                ),
                onClick = onOpenPremium,
            )

            SettingsSectionTitle(R.string.settings_section_support)
            SettingsRow(
                label = stringResource(R.string.settings_contact_support_label),
                onClick = onContactSupport,
            )

            SettingsSectionTitle(R.string.settings_section_about)
            if (privacyOptionsRequired) {
                SettingsRow(
                    label = stringResource(R.string.settings_privacy_choices_label),
                    onClick = onOpenPrivacyOptions,
                    modifier = Modifier.testTag(PRIVACY_CHOICES_TAG),
                )
            }
            SettingsRow(
                label = stringResource(R.string.about_section_privacy_policy),
                onClick = onOpenPrivacyPolicy,
            )
            SettingsRow(
                label = stringResource(R.string.about_section_data_attribution),
                onClick = onOpenDataAttribution,
            )
            SettingsRow(
                label = stringResource(R.string.about_section_open_source_licences),
                onClick = onOpenOpenSourceLicences,
            )
            SettingsRow(
                label = stringResource(R.string.settings_reviewer_access_label),
                value = stringResource(
                    if (state.reviewerAccessActive) R.string.settings_reviewer_access_status_active
                    else R.string.settings_reviewer_access_status_inactive,
                ),
                onClick = {
                    onClearReviewerAccessOperation()
                    showReviewerAccessDialog = true
                },
                modifier = Modifier.testTag(REVIEWER_ACCESS_ROW_TAG),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.about_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            selectedLanguage = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelected = {
                showLanguageDialog = false
                onLanguageSelected(it)
            },
        )
    }
    if (showAppearanceDialog) {
        AppearanceDialog(
            selectedMode = state.appearanceMode,
            hasPremiumAccess = state.entitlement.hasPremiumAccess,
            onDismiss = { showAppearanceDialog = false },
            onPremiumRequested = {
                showAppearanceDialog = false
                onOpenPremium()
            },
            onSelected = {
                showAppearanceDialog = false
                onAppearanceSelected(it)
            },
        )
    }
    if (showReviewerAccessDialog) {
        ReviewerAccessDialog(
            state = state,
            onDismiss = {
                showReviewerAccessDialog = false
                onClearReviewerAccessOperation()
            },
            onActivate = onActivateReviewerAccess,
            onDeactivate = onDeactivateReviewerAccess,
            onClearOperation = onClearReviewerAccessOperation,
        )
    }
}

@Composable
private fun ReviewerAccessDialog(
    state: AboutUiState,
    onDismiss: () -> Unit,
    onActivate: (String) -> Unit,
    onDeactivate: () -> Unit,
    onClearOperation: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val operation = state.reviewerAccessOperation
    val inProgress = operation.isInProgress
    val activationSucceeded = operation is ReviewerAccessOperationState.Activated
    val deactivationSucceeded = operation is ReviewerAccessOperationState.Deactivated
    val showActiveControls = when (operation) {
        ReviewerAccessOperationState.Activating,
        ReviewerAccessOperationState.Activated,
        ReviewerAccessOperationState.Deactivated,
        -> false
        ReviewerAccessOperationState.Deactivating,
        ReviewerAccessOperationState.DeactivationFailed,
        -> true
        else -> state.reviewerAccessActive
    }

    LaunchedEffect(operation) {
        if (activationSucceeded || deactivationSucceeded) code = ""
    }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(stringResource(R.string.settings_reviewer_access_label)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    activationSucceeded -> ReviewerResultText(
                        text = stringResource(R.string.settings_reviewer_access_activation_success),
                    )
                    deactivationSucceeded -> ReviewerResultText(
                        text = stringResource(R.string.settings_reviewer_access_deactivation_success),
                    )
                    showActiveControls -> {
                        Text(
                            text = stringResource(R.string.settings_reviewer_access_active_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        when (operation) {
                            ReviewerAccessOperationState.Deactivating -> ReviewerProgress(
                                stringResource(R.string.settings_reviewer_access_deactivating),
                            )
                            ReviewerAccessOperationState.DeactivationFailed -> ReviewerResultText(
                                text = stringResource(R.string.settings_reviewer_access_deactivation_error),
                                error = true,
                            )
                            else -> Unit
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.settings_reviewer_access_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = {
                                code = it
                                if (
                                    operation is ReviewerAccessOperationState.InvalidCode ||
                                    operation is ReviewerAccessOperationState.TemporarilyUnavailable
                                ) {
                                    onClearOperation()
                                }
                            },
                            enabled = !inProgress,
                            isError = operation is ReviewerAccessOperationState.InvalidCode,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (!inProgress) onActivate(code) },
                            ),
                            label = { Text(stringResource(R.string.settings_reviewer_access_code_label)) },
                            modifier = Modifier.fillMaxWidth().testTag(REVIEWER_ACCESS_CODE_TAG),
                        )
                        when (operation) {
                            ReviewerAccessOperationState.Activating -> ReviewerProgress(
                                stringResource(R.string.settings_reviewer_access_activating),
                            )
                            ReviewerAccessOperationState.InvalidCode -> ReviewerResultText(
                                text = stringResource(R.string.settings_reviewer_access_invalid_code),
                                error = true,
                            )
                            ReviewerAccessOperationState.TemporarilyUnavailable -> ReviewerResultText(
                                text = stringResource(R.string.settings_reviewer_access_unavailable),
                                error = true,
                            )
                            else -> Unit
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                activationSucceeded || deactivationSucceeded -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_reviewer_access_close))
                }
                showActiveControls -> TextButton(
                    onClick = onDeactivate,
                    enabled = !inProgress,
                    modifier = Modifier.testTag(REVIEWER_ACCESS_DEACTIVATE_TAG),
                ) {
                    Text(stringResource(R.string.settings_reviewer_access_deactivate))
                }
                else -> TextButton(
                    onClick = { onActivate(code) },
                    enabled = !inProgress,
                    modifier = Modifier.testTag(REVIEWER_ACCESS_ACTIVATE_TAG),
                ) {
                    Text(stringResource(R.string.settings_reviewer_access_activate))
                }
            }
        },
        dismissButton = {
            if (!activationSucceeded && !deactivationSucceeded) {
                TextButton(onClick = onDismiss, enabled = !inProgress) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun ReviewerProgress(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .testTag(REVIEWER_ACCESS_PROGRESS_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReviewerResultText(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SettingsSectionTitle(@StringRes titleRes: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LanguageDialog(
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_label)) },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioOption(
                    label = stringResource(R.string.settings_language_option_english),
                    selected = selectedLanguage == "en",
                    onClick = { onSelected("en") },
                    modifier = Modifier.testTag(LANGUAGE_OPTION_EN_TAG),
                )
                RadioOption(
                    label = stringResource(R.string.settings_language_option_swedish),
                    selected = selectedLanguage == "sv",
                    onClick = { onSelected("sv") },
                    modifier = Modifier.testTag(LANGUAGE_OPTION_SV_TAG),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AppearanceDialog(
    selectedMode: AppearanceMode,
    hasPremiumAccess: Boolean,
    onDismiss: () -> Unit,
    onPremiumRequested: () -> Unit,
    onSelected: (AppearanceMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_appearance_label)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppearanceMode.entries.forEach { mode ->
                    RadioOption(
                        label = stringResource(appearanceLabelRes(mode)),
                        selected = selectedMode == mode,
                        onClick = {
                            if (mode == AppearanceMode.StockholmNight && !hasPremiumAccess) {
                                onPremiumRequested()
                            } else {
                                onSelected(mode)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@StringRes
internal fun appearanceLabelRes(mode: AppearanceMode): Int = when (mode) {
    AppearanceMode.System -> R.string.settings_appearance_system
    AppearanceMode.Light -> R.string.settings_appearance_light
    AppearanceMode.Dark -> R.string.settings_appearance_dark
    AppearanceMode.StockholmNight -> R.string.settings_appearance_stockholm_night
}

@StringRes
internal fun notificationLabelRes(availability: NotificationAvailability): Int = when (availability) {
    NotificationAvailability.Available -> R.string.settings_notifications_on
    NotificationAvailability.PermissionMissing -> R.string.settings_notifications_permission_required
    NotificationAvailability.AppDisabled,
    NotificationAvailability.ChannelDisabled,
    -> R.string.settings_notifications_off
}

@StringRes
internal fun liveUpdatesLabelRes(enabled: Boolean, sdkInt: Int): Int = when {
    sdkInt < Build.VERSION_CODES.BAKLAVA -> R.string.settings_live_updates_requires_android_16
    enabled -> R.string.settings_notifications_on
    else -> R.string.settings_notifications_off
}
