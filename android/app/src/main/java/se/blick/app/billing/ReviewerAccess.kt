package se.blick.app.billing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.blick.app.data.remote.BlickApiClient

const val REVIEWER_ACCESS_CODE_MIN_LENGTH = 32
const val REVIEWER_ACCESS_CODE_MAX_LENGTH = 256
internal const val REVIEWER_ACCESS_VALIDATION_TIMEOUT_MS = 10_000L

enum class ReviewerAccessActivationResult {
    Activated,
    InvalidCode,
    TemporarilyUnavailable,
}

/** Stores only the locally issued grant, never the reviewer credential itself. */
interface ReviewerAccessGrantStore {
    fun readActive(): Boolean
    fun writeActive(active: Boolean)
}

@Singleton
class PreferencesReviewerAccessGrantStore @Inject constructor(
    @ApplicationContext context: Context,
) : ReviewerAccessGrantStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun readActive(): Boolean = preferences.getBoolean(KEY_ACTIVE, false)

    @Suppress("UseKtx") // The KTX edit helper discards commit's result; this grant must fail closed.
    override fun writeActive(active: Boolean) {
        check(preferences.edit().putBoolean(KEY_ACTIVE, active).commit()) {
            "Unable to persist reviewer access"
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "reviewer_access_grant"
        const val KEY_ACTIVE = "active"
    }
}

@Singleton
class ReviewerAccessController @Inject constructor(
    private val apiClient: BlickApiClient,
    private val store: ReviewerAccessGrantStore,
) {
    private val mutationMutex = Mutex()
    private val _active = MutableStateFlow(store.readActive())
    val active: StateFlow<Boolean> = _active.asStateFlow()

    suspend fun activate(code: String): ReviewerAccessActivationResult = mutationMutex.withLock {
        val normalized = normalizeReviewerAccessCode(code)
            ?: return ReviewerAccessActivationResult.InvalidCode
        try {
            val response = withTimeoutOrNull(REVIEWER_ACCESS_VALIDATION_TIMEOUT_MS) {
                apiClient.validateReviewerAccess(normalized)
            } ?: return ReviewerAccessActivationResult.TemporarilyUnavailable
            if (!response.authorized) return ReviewerAccessActivationResult.InvalidCode
            withContext(Dispatchers.IO) { store.writeActive(true) }
            _active.value = true
            ReviewerAccessActivationResult.Activated
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ReviewerAccessActivationResult.TemporarilyUnavailable
        }
    }

    suspend fun deactivate() = mutationMutex.withLock {
        withContext(Dispatchers.IO) { store.writeActive(false) }
        _active.value = false
    }
}

internal fun normalizeReviewerAccessCode(code: String): String? =
    code.trim().takeIf {
        it.length in REVIEWER_ACCESS_CODE_MIN_LENGTH..REVIEWER_ACCESS_CODE_MAX_LENGTH
    }

/** The only composition point for Play, reviewer, and debug Premium sources. */
internal fun effectivePremiumEntitlement(
    playEntitlement: EntitlementState,
    reviewerAccessActive: Boolean,
    debugOverrideEnabled: Boolean,
): EntitlementState = if (reviewerAccessActive || debugOverrideEnabled) {
    EntitlementState.Premium
} else {
    playEntitlement
}
