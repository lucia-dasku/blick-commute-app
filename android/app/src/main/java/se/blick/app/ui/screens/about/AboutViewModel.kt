package se.blick.app.ui.screens.about

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.ui.theme.AppearanceMode
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import javax.inject.Inject

data class AboutUiState(
    val appearanceMode: AppearanceMode = AppearanceMode.System,
    val entitlement: EntitlementState = EntitlementState.Loading,
    val notificationAvailability: NotificationAvailability = NotificationAvailability.Available,
    val liveUpdatesEnabled: Boolean = false,
)

/** Owns the small pieces of state displayed by Settings while keeping their existing sources
 * authoritative: AppCompat for language, Preferences DataStore for appearance, Android for
 * notification capability, and the billing repository for Premium. */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val routineWidgetUpdater: RoutineWidgetUpdater,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val premiumEntitlementRepository: PremiumEntitlementRepository,
    private val notificationAvailabilityChecker: NotificationAvailabilityChecker,
    private val promotedNotificationChecker: PromotedNotificationChecker,
) : ViewModel() {

    private val notificationAvailability = MutableStateFlow(notificationAvailabilityChecker.check())
    private val liveUpdatesEnabled = MutableStateFlow(promotedNotificationChecker.isPromotable())

    val uiState = combine(
        appSettingsDataStore.settings,
        premiumEntitlementRepository.entitlement,
        notificationAvailability,
        liveUpdatesEnabled,
    ) { settings, entitlement, notifications, liveUpdates ->
        AboutUiState(
            appearanceMode = AppearanceMode.from(
                useDarkTheme = settings.useDarkTheme,
                useStockholmNightTheme = settings.useStockholmNightTheme,
                hasPremiumAccess = entitlement.hasPremiumAccess,
            ),
            entitlement = entitlement,
            notificationAvailability = notifications,
            liveUpdatesEnabled = liveUpdates,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AboutUiState(
            notificationAvailability = notificationAvailability.value,
            entitlement = premiumEntitlementRepository.entitlement.value,
            liveUpdatesEnabled = liveUpdatesEnabled.value,
        ),
    )

    fun onLanguageSelected(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        viewModelScope.launch {
            runWidgetUpdateSafely { routineWidgetUpdater.refreshPresentation() }
        }
    }

    fun onAppearanceSelected(mode: AppearanceMode) {
        viewModelScope.launch {
            if (mode == AppearanceMode.StockholmNight) {
                if (premiumEntitlementRepository.entitlement.value.hasPremiumAccess) {
                    appSettingsDataStore.setUseStockholmNightTheme(true)
                }
            } else {
                appSettingsDataStore.setUseStockholmNightTheme(false)
                appSettingsDataStore.setUseDarkTheme(mode.useDarkTheme)
            }
        }
    }

    /** Re-read on resume so returning from Android Settings immediately reflects any change. */
    fun refreshNotificationAvailability() {
        notificationAvailability.value = notificationAvailabilityChecker.check()
        liveUpdatesEnabled.value = promotedNotificationChecker.isPromotable()
    }
}
