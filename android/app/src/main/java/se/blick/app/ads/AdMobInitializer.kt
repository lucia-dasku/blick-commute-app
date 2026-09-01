package se.blick.app.ads

import android.content.Context
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Initializes GMA Next-Gen at most once, only after UMP allows an advertising request. */
@Singleton
class AdMobInitializer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val initializationMutex = Mutex()
    private var attempted = false
    private var initialized = false

    suspend fun initializeIfNeeded(): Boolean = initializationMutex.withLock {
        if (initialized) return@withLock true
        if (attempted) return@withLock false
        attempted = true

        initialized = withContext(Dispatchers.IO + NonCancellable) {
            try {
                MobileAds.initialize(
                    context,
                    InitializationConfig.Builder(ADMOB_APP_ID).build(),
                )
                true
            } catch (error: Exception) {
                Log.d(TAG, "Mobile ads initialization failed (${error.javaClass.simpleName}).")
                false
            }
        }
        initialized
    }

    private companion object {
        const val TAG = "AdMobInitializer"
        const val ADMOB_APP_ID = "ca-app-pub-2107592277107216~9098333187"
    }
}
