package se.blick.app.ui.screens.about

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.blick.app.billing.EntitlementState
import se.blick.app.billing.PremiumEntitlementRepository
import se.blick.app.billing.ReviewerAccessActivationResult
import se.blick.app.billing.hasPremiumAccess
import se.blick.app.data.local.datastore.AppSettingsDataStore
import se.blick.app.notification.NotificationAvailability
import se.blick.app.notification.NotificationAvailabilityChecker
import se.blick.app.notification.PromotedNotificationChecker
import se.blick.app.scheduling.EntitlementChangeReconciler
import se.blick.app.ui.theme.AppearanceMode
import se.blick.app.widget.RoutineWidgetUpdater
import se.blick.app.widget.runWidgetUpdateSafely
import javax.inject.Inject

sealed interface ReviewerAccessOperationState {
    data object Idle : ReviewerAccessOperationState
    data object Activating : ReviewerAccessOperationState
    data object Activated : ReviewerAccessOperationState
    data object InvalidCode : ReviewerAccessOperationState
    data object TemporarilyUnavailable : ReviewerAccessOperationState
    data object Deactivating : ReviewerAccessOperationState
    data object Deactivated : ReviewerAccessOperationState
    data object DeactivationFailed : ReviewerAccessOperationState
}

internal val ReviewerAccessOperationState.isInProgress: Boolean
    get() = this is ReviewerAccessOperationState.Activating ||
        this is ReviewerAccessOperationState.Deactivating

data class AboutUiState(
    val appearanceMode: AppearanceMode = AppearanceMode.System,
    val entitlement: EntitlementState = EntitlementState.Loading,
    val notificationAvailability: NotificationAvailability = NotificationAvailability.Available,
    val liveUpdatesEnabled: Boolean = false,
    val reviewerAccessActive: Boolean = false,
    val reviewerAccessOperation: ReviewerAccessOperationState = ReviewerAccessOperationState.Idle,
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
    private val entitlementChangeReconciler: EntitlementChangeReconciler,
) : ViewModel() {

    private val notificationAvailability = MutableStateFlow(notificationAvailabilityChecker.check())
    private val liveUpdatesEnabled = MutableStateFlow(promotedNotificationChecker.isPromotable())
    private val reviewerAccessOperation = MutableStateFlow<ReviewerAccessOperationState>(
        ReviewerAccessOperationState.Idle,
    )

    private val baseUiState = combine(
        appSettingsDataStore.settings,
        premiumEntitlementRepository.entitlement,
        premiumEntitlementRepository.reviewerAccessActive,
        notificationAvailability,
        liveUpdatesEnabled,
    ) { settings, entitlement, reviewerAccessActive, notifications, liveUpdates ->
        AboutUiState(
            appearanceMode = AppearanceMode.from(
                useDarkTheme = settings.useDarkTheme,
                useStockholmNightTheme = settings.useStockholmNightTheme,
                hasPremiumAccess = entitlement.hasPremiumAccess,
            ),
            entitlement = entitlement,
            notificationAvailability = notifications,
            liveUpdatesEnabled = liveUpdates,
            reviewerAccessActive = reviewerAccessActive,
        )
    }

    val uiState = combine(baseUiState, reviewerAccessOperation) { state, operation ->
        state.copy(reviewerAccessOperation = operation)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AboutUiState(
            notificationAvailability = notificationAvailability.value,
            entitlement = premiumEntitlementRepository.entitlement.value,
            liveUpdatesEnabled = liveUpdatesEnabled.value,
            reviewerAccessActive = premiumEntitlementRepository.reviewerAccessActive.value,
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
            runWidgetUpdateSafely { routineWidgetUpdater.refreshPresentation() }
        }
    }

    fun activateReviewerAccess(code: String) {
        if (reviewerAccessOperation.value.isInProgress) return
        reviewerAccessOperation.value = ReviewerAccessOperationState.Activating
        viewModelScope.launch {
            val result = try {
                premiumEntitlementRepository.activateReviewerAccess(code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReviewerAccessActivationResult.TemporarilyUnavailable
            }
            reviewerAccessOperation.value = when (result) {
                ReviewerAccessActivationResult.Activated -> {
                    reconcileEntitlementChangeSafely()
                    ReviewerAccessOperationState.Activated
                }
                ReviewerAccessActivationResult.InvalidCode -> ReviewerAccessOperationState.InvalidCode
                ReviewerAccessActivationResult.TemporarilyUnavailable ->
                    ReviewerAccessOperationState.TemporarilyUnavailable
            }
        }
    }

    fun deactivateReviewerAccess() {
        if (reviewerAccessOperation.value.isInProgress) return
        reviewerAccessOperation.value = ReviewerAccessOperationState.Deactivating
        viewModelScope.launch {
            reviewerAccessOperation.value = try {
                premiumEntitlementRepository.deactivateReviewerAccess()
                reconcileEntitlementChangeSafely()
                ReviewerAccessOperationState.Deactivated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReviewerAccessOperationState.DeactivationFailed
            }
        }
    }

    fun clearReviewerAccessOperation() {
        if (!reviewerAccessOperation.value.isInProgress) {
            reviewerAccessOperation.value = ReviewerAccessOperationState.Idle
        }
    }

    private suspend fun reconcileEntitlementChangeSafely() {
        try {
            entitlementChangeReconciler.reconcileAfterEntitlementChange()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The persisted entitlement change remains authoritative. Startup reconciliation
            // will retry routine scheduling if this best-effort immediate pass fails.
        }
    }

    /** Re-read on resume so returning from Android Settings immediately reflects any change. */
    fun refreshNotificationAvailability() {
        notificationAvailability.value = notificationAvailabilityChecker.check()
        liveUpdatesEnabled.value = promotedNotificationChecker.isPromotable()
    }
}
