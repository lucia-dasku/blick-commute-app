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
    ) { entitlement, price, debugEnabled ->
        PremiumUiState(entitlement, price, repository.debugOverrideAvailable, debugEnabled)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PremiumUiState(
            repository.entitlement.value,
            repository.localizedPrice.value,
            repository.debugOverrideAvailable,
            repository.debugOverrideEnabled.value,
        ),
    )

    fun restore() { viewModelScope.launch { repository.restore() } }
    fun launchPurchase(activity: android.app.Activity) = repository.launchPurchase(activity)
    fun toggleDebugPremium() = repository.setDebugPremium(!repository.debugOverrideEnabled.value)
}
