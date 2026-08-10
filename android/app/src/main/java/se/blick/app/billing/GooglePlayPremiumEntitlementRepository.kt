package se.blick.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import se.blick.app.BuildConfig
import se.blick.app.data.remote.BlickApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class GooglePlayPremiumEntitlementRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val apiClient: BlickApiClient,
) : PremiumEntitlementRepository, PurchasesUpdatedListener {
    private val preferences = context.getSharedPreferences("premium_entitlement_cache", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entitlement = MutableStateFlow<EntitlementState>(EntitlementState.Loading)
    override val entitlement: StateFlow<EntitlementState> = _entitlement.asStateFlow()
    private val _localizedPrice = MutableStateFlow<String?>(null)
    override val localizedPrice: StateFlow<String?> = _localizedPrice.asStateFlow()
    private val _debugOverrideEnabled = MutableStateFlow(
        BuildConfig.DEBUG && preferences.getBoolean(KEY_DEBUG_PREMIUM, false),
    )
    override val debugOverrideEnabled: StateFlow<Boolean> = _debugOverrideEnabled.asStateFlow()
    override val debugOverrideAvailable: Boolean = BuildConfig.DEBUG
    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun refresh() {
        if (_debugOverrideEnabled.value) {
            _entitlement.value = EntitlementState.Premium
            return
        }
        try {
            ensureConnected()
            queryProduct()
            val purchases = queryPurchases()
            applyPurchases(purchases)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _entitlement.value = EntitlementState.TemporarilyUnavailable(lastVerifiedPremium())
        }
    }

    override suspend fun restore() = refresh()

    override fun launchPurchase(activity: Activity) {
        val details = productDetails ?: run {
            scope.launch { refresh() }
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { offerToken?.let(::setOfferToken) }
            .build()
        billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
    }

    override fun setDebugPremium(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        preferences.edit().putBoolean(KEY_DEBUG_PREMIUM, enabled).apply()
        _debugOverrideEnabled.value = enabled
        if (enabled) {
            _entitlement.value = EntitlementState.Premium
        } else {
            _entitlement.value = EntitlementState.Loading
            scope.launch { refresh() }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (_debugOverrideEnabled.value) {
            _entitlement.value = EntitlementState.Premium
            return
        }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { applyPurchases(purchases.orEmpty()) }
            BillingClient.BillingResponseCode.USER_CANCELED -> scope.launch { refresh() }
            else -> _entitlement.value = EntitlementState.TemporarilyUnavailable(lastVerifiedPremium())
        }
    }

    private suspend fun applyPurchases(purchases: List<Purchase>) {
        val relevant = purchases.filter { PREMIUM_PRODUCT_ID in it.products }
        val purchased = relevant.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (purchased != null) {
            try {
                val verification = apiClient.verifyPurchase(PREMIUM_PRODUCT_ID, purchased.purchaseToken)
                if (verification.verified && verification.state == "PURCHASED") {
                    cacheVerified(true)
                    _entitlement.value = BillingEntitlementReducer.reduce(
                        PurchaseObservation.PURCHASED, VerificationObservation.VERIFIED, lastVerifiedPremium(),
                    )
                } else {
                    cacheVerified(false)
                    _entitlement.value = BillingEntitlementReducer.reduce(
                        PurchaseObservation.PURCHASED, VerificationObservation.REJECTED, lastVerifiedPremium(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _entitlement.value = BillingEntitlementReducer.reduce(
                    PurchaseObservation.PURCHASED, VerificationObservation.UNAVAILABLE, lastVerifiedPremium(),
                )
            }
            return
        }
        if (relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            _entitlement.value = BillingEntitlementReducer.reduce(
                PurchaseObservation.PENDING, VerificationObservation.NOT_NEEDED, lastVerifiedPremium(),
            )
            return
        }
        // A successful Play query returning no owned product is authoritative revocation/refund.
        cacheVerified(false)
        _entitlement.value = BillingEntitlementReducer.reduce(
            PurchaseObservation.NONE, VerificationObservation.NOT_NEEDED, lastVerifiedPremium(),
        )
    }

    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        val result = suspendCancellableCoroutine<BillingResult> { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result)
                }
                override fun onBillingServiceDisconnected() = Unit
            })
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) error("Billing unavailable")
    }

    private suspend fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PREMIUM_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = suspendCancellableCoroutine<Pair<BillingResult, List<ProductDetails>>> { continuation ->
            billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            ) { billingResult, detailsResult ->
                if (continuation.isActive) continuation.resume(billingResult to detailsResult.productDetailsList)
            }
        }
        if (result.first.responseCode != BillingClient.BillingResponseCode.OK) return
        productDetails = result.second.firstOrNull()
        val details = productDetails ?: return
        val multiOffer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        val legacyOffer = details.oneTimePurchaseOfferDetails
        val offer = multiOffer ?: legacyOffer
        offerToken = multiOffer?.offerToken
        _localizedPrice.value = offer?.formattedPrice
    }

    private suspend fun queryPurchases(): List<Purchase> =
        suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(purchases)
                else continuation.resumeWith(Result.failure(IllegalStateException("Billing query failed")))
            }
        }

    private fun cacheVerified(premium: Boolean) {
        preferences.edit().putBoolean(KEY_PREMIUM, premium).putBoolean(KEY_HAS_VALUE, true).apply()
    }

    private fun lastVerifiedPremium(): Boolean =
        preferences.getBoolean(KEY_HAS_VALUE, false) && preferences.getBoolean(KEY_PREMIUM, false)

    private companion object {
        const val KEY_PREMIUM = "last_verified_premium"
        const val KEY_HAS_VALUE = "has_verified_entitlement"
        const val KEY_DEBUG_PREMIUM = "debug_premium_override"
    }
}
