package se.blick.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdConsentState(
    val canRequestAds: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
    /** Changes after a consent form closes so a future banner cannot reuse stale request state. */
    val revision: Long = 0,
)

/**
 * Process-wide UMP coordinator. UMP remains authoritative; no consent strings or duplicate
 * preferences are read or stored by Blick.
 */
@Singleton
class AdConsentManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val _state = MutableStateFlow(AdConsentState())
    val state: StateFlow<AdConsentState> = _state.asStateFlow()

    private var requestInProgress = false
    private var updateCompleted = false
    private var consentFormHandledForUpdate = false
    private var consentFormInProgress = false
    private var advertisingRelevant = false
    private var latestActivity = WeakReference<Activity>(null)

    /** Refreshes UMP information once per process launch without presenting a form by itself. */
    fun requestConsentInfoUpdate(activity: Activity) {
        latestActivity = WeakReference(activity)
        if (requestInProgress || updateCompleted) return

        requestInProgress = true
        _state.value = _state.value.copy(
            canRequestAds = false,
            privacyOptionsRequired = isPrivacyOptionsRequired(),
        )
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                requestInProgress = false
                updateCompleted = true
                publish(canRequestAds = false)
                if (advertisingRelevant) gatherConsentIfNeeded()
            },
            { error ->
                requestInProgress = false
                updateCompleted = true
                consentFormHandledForUpdate = true
                Log.d(TAG, "Consent information update failed (code=${error.errorCode}).")
                // A prior session can still contain valid consent after a refresh error.
                publish(canRequestAds = advertisingRelevant && consentInformation.canRequestAds())
            },
        )
    }

    /**
     * Controls whether the required first-use form is relevant to the current Basic ad surface.
     * Turning this off immediately revokes the app's exposed permission to request a banner.
     */
    fun setAdvertisingRelevant(activity: Activity, relevant: Boolean) {
        latestActivity = WeakReference(activity)
        advertisingRelevant = relevant
        if (!relevant) {
            publish(canRequestAds = false)
            return
        }
        if (updateCompleted) gatherConsentIfNeeded()
    }

    fun showPrivacyOptions(activity: Activity) {
        if (!isPrivacyOptionsRequired()) return

        // Stop exposing the old request state while the user is changing privacy choices.
        publish(canRequestAds = false)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) {
                Log.d(TAG, "Privacy options form failed (code=${error.errorCode}).")
            }
            publish(
                canRequestAds = advertisingRelevant && consentInformation.canRequestAds(),
                incrementRevision = true,
            )
        }
    }

    private fun gatherConsentIfNeeded() {
        if (consentFormHandledForUpdate || consentFormInProgress) {
            publish(canRequestAds = advertisingRelevant && consentInformation.canRequestAds())
            return
        }
        val activity = latestActivity.get() ?: return

        consentFormHandledForUpdate = true
        consentFormInProgress = true
        publish(canRequestAds = false)
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
            consentFormInProgress = false
            if (error != null) {
                Log.d(TAG, "Required consent form failed (code=${error.errorCode}).")
            }
            // This check is required even on error because an earlier consent can remain valid.
            publish(
                canRequestAds = advertisingRelevant && consentInformation.canRequestAds(),
                incrementRevision = true,
            )
        }
    }

    private fun publish(canRequestAds: Boolean, incrementRevision: Boolean = false) {
        val previous = _state.value
        _state.value = AdConsentState(
            canRequestAds = canRequestAds,
            privacyOptionsRequired = isPrivacyOptionsRequired(),
            revision = previous.revision + if (incrementRevision) 1 else 0,
        )
    }

    private fun isPrivacyOptionsRequired(): Boolean =
        consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    private companion object {
        const val TAG = "AdConsentManager"
    }
}
