package se.blick.app.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

const val PREMIUM_PRODUCT_ID = "blick_premium_lifetime"

sealed interface EntitlementState {
    data object Loading : EntitlementState
    data object Free : EntitlementState
    data object Premium : EntitlementState
    data object Pending : EntitlementState
    data class TemporarilyUnavailable(val lastVerifiedPremium: Boolean) : EntitlementState
}

val EntitlementState.hasPremiumAccess: Boolean
    get() = this is EntitlementState.Premium ||
        (this is EntitlementState.TemporarilyUnavailable && lastVerifiedPremium)

interface PremiumEntitlementRepository {
    val entitlement: StateFlow<EntitlementState>
    val localizedPrice: StateFlow<String?>
    /** Debug builds can expose a local entitlement override for device UI testing without
     * weakening the Play-verified release path. Release implementations always return false. */
    val debugOverrideAvailable: Boolean get() = false
    val debugOverrideEnabled: StateFlow<Boolean> get() = NO_DEBUG_OVERRIDE
    suspend fun refresh()
    suspend fun restore()
    fun launchPurchase(activity: Activity)
    fun setDebugPremium(enabled: Boolean) = Unit
}

private val NO_DEBUG_OVERRIDE = MutableStateFlow(false)

object FreePremiumEntitlementRepository : PremiumEntitlementRepository {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<EntitlementState>(EntitlementState.Free)
    override val entitlement: StateFlow<EntitlementState> = state
    override val localizedPrice: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override suspend fun refresh() = Unit
    override suspend fun restore() = Unit
    override fun launchPurchase(activity: Activity) = Unit
}
