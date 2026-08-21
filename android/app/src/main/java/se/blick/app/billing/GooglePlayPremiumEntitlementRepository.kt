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
import kotlinx.coroutines.withTimeoutOrNull
import se.blick.app.data.remote.BlickApiClient
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
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
    private val _debugOverrideEnabled = MutableStateFlow(readDebugPremiumOverride(preferences))
    override val debugOverrideEnabled: StateFlow<Boolean> = _debugOverrideEnabled.asStateFlow()
    override val debugOverrideAvailable: Boolean = DEBUG_PREMIUM_OVERRIDE_AVAILABLE
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
        runBoundedPremiumRefresh(
            lastVerifiedPremium = ::lastVerifiedPremium,
            updateEntitlement = { _entitlement.value = it },
        ) {
            ensureConnected()
            queryProduct()
            val purchases = queryPurchases()
            applyPurchases(purchases)
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
        if (!DEBUG_PREMIUM_OVERRIDE_AVAILABLE) return
        writeDebugPremiumOverride(preferences, enabled)
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
                    cacheVerified(true, Instant.parse(verification.verifiedAt).toEpochMilli())
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

    private fun cacheVerified(premium: Boolean, verifiedAtMillis: Long = 0L) {
        preferences.edit()
            .putBoolean(KEY_PREMIUM, premium)
            .putBoolean(KEY_HAS_VALUE, true)
            .putLong(KEY_VERIFIED_AT, if (premium) verifiedAtMillis else 0L)
            .apply()
    }

    private fun lastVerifiedPremium(): Boolean =
        isVerifiedPremiumCacheFresh(
            hasValue = preferences.getBoolean(KEY_HAS_VALUE, false),
            premium = preferences.getBoolean(KEY_PREMIUM, false),
            verifiedAtMillis = preferences.getLong(KEY_VERIFIED_AT, 0L),
            nowMillis = System.currentTimeMillis(),
        )

    private companion object {
        const val KEY_PREMIUM = "last_verified_premium"
        const val KEY_HAS_VALUE = "has_verified_entitlement"
        const val KEY_VERIFIED_AT = "last_google_verified_at"
    }
}

/**
 * Overall wall-clock bound for one Play entitlement refresh: Billing connection, product query,
 * purchase query, and (when a purchase exists) backend verification together. The backend call
 * already has its own 10-second whole-call timeout; the remaining five seconds allow the normally
 * fast Play operations to complete without letting startup/foreground recovery wait indefinitely.
 */
internal const val PREMIUM_REFRESH_TIMEOUT_MS = 15_000L

/**
 * Runs the complete entitlement refresh under one bound and converts only this bound's expiry, or
 * an ordinary Billing/backend failure, to the existing cache-aware fail-soft state.
 * [withTimeoutOrNull] returns null only for the timeout it creates; parent cancellation and a
 * timeout thrown by nested work still propagate as [CancellationException].
 */
internal suspend fun runBoundedPremiumRefresh(
    timeoutMillis: Long = PREMIUM_REFRESH_TIMEOUT_MS,
    lastVerifiedPremium: () -> Boolean,
    updateEntitlement: (EntitlementState) -> Unit,
    refresh: suspend () -> Unit,
) {
    try {
        val completed = withTimeoutOrNull(timeoutMillis) {
            refresh()
            true
        } ?: false
        if (!completed) {
            updateEntitlement(EntitlementState.TemporarilyUnavailable(lastVerifiedPremium()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        updateEntitlement(EntitlementState.TemporarilyUnavailable(lastVerifiedPremium()))
    }
}

internal const val PREMIUM_OFFLINE_GRACE_MS = 24L * 60L * 60L * 1_000L
private const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1_000L

internal fun isVerifiedPremiumCacheFresh(
    hasValue: Boolean,
    premium: Boolean,
    verifiedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    if (!hasValue || !premium || verifiedAtMillis <= 0L) return false
    val age = nowMillis - verifiedAtMillis
    return age >= -MAX_CLOCK_SKEW_MS && age <= PREMIUM_OFFLINE_GRACE_MS
}
