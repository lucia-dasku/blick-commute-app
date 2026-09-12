package se.blick.app.ui.screens.premium

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.blick.app.billing.PremiumEntitlementRepository
import javax.inject.Inject

data class PremiumUiState(
    val entitlement: se.blick.app.billing.EntitlementState,
    val localizedPrice: String?,
    val debugOverrideAvailable: Boolean = false,
    val debugOverrideEnabled: Boolean = false,
    val reviewerAccessActive: Boolean = false,
    val isRestoring: Boolean = false,
)

@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val repository: PremiumEntitlementRepository,
) : ViewModel() {
    val uiState = combine(
        repository.entitlement,
        repository.localizedPrice,
        repository.debugOverrideEnabled,
        repository.reviewerAccessActive,
    ) { entitlement, price, debugEnabled, reviewerAccessActive ->
        PremiumUiState(
            entitlement = entitlement,
            localizedPrice = price,
            debugOverrideAvailable = repository.debugOverrideAvailable,
            debugOverrideEnabled = debugEnabled,
            reviewerAccessActive = reviewerAccessActive,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PremiumUiState(
            repository.entitlement.value,
            repository.localizedPrice.value,
            repository.debugOverrideAvailable,
            repository.debugOverrideEnabled.value,
            repository.reviewerAccessActive.value,
        ),
    )

    fun restore() { viewModelScope.launch { repository.restore() } }
    fun launchPurchase(activity: android.app.Activity) = repository.launchPurchase(activity)
    fun toggleDebugPremium() = repository.setDebugPremium(!repository.debugOverrideEnabled.value)
}
