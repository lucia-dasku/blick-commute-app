package se.blick.app.ads

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

internal const val BANNER_CONTAINER_TEST_TAG = "ad-banner-container"

/** Keeps navigation content above the banner rather than overlaying it. */
@Composable
fun BannerAwareContent(
    bannerEligible: Boolean,
    content: @Composable () -> Unit,
    banner: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
        }
        if (bannerEligible) banner()
    }
}

/**
 * A standard 320x50dp Next-Gen banner. Its space appears only after a successful load; leaving
 * composition (including an in-process Premium transition) destroys the SDK view immediately.
 */
@Composable
fun AdBanner(
    adUnitId: String,
    consentRevision: Long,
    initializer: AdMobInitializer,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val adView = remember(adUnitId, consentRevision, context) { AdView(context) }
    var adsInitialized by remember(adView) { mutableStateOf(false) }
    var adLoaded by remember(adView) { mutableStateOf(false) }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    LaunchedEffect(adView, isPreview) {
        if (isPreview) return@LaunchedEffect
        adsInitialized = initializer.initializeIfNeeded()
        if (!adsInitialized) return@LaunchedEffect

        val request = BannerAdRequest.Builder(adUnitId, AdSize.BANNER).build()
        adView.loadAd(
            request,
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    adLoaded = true
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    adLoaded = false
                    Log.d(TAG, "Banner request failed (code=${adError.code}).")
                }
            },
        )
    }

    if (adsInitialized && adLoaded) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp)
                .testTag(BANNER_CONTAINER_TEST_TAG),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { adView },
                modifier = Modifier
                    .width(320.dp)
                    .height(50.dp),
            )
        }
    }
}

private const val TAG = "AdBanner"
