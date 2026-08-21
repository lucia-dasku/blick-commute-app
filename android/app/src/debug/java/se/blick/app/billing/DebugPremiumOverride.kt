package se.blick.app.billing

import android.content.SharedPreferences

internal const val DEBUG_PREMIUM_OVERRIDE_AVAILABLE = true
private const val KEY_DEBUG_PREMIUM = "debug_premium_override"

internal fun readDebugPremiumOverride(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_DEBUG_PREMIUM, false)

internal fun writeDebugPremiumOverride(preferences: SharedPreferences, enabled: Boolean) {
    preferences.edit().putBoolean(KEY_DEBUG_PREMIUM, enabled).apply()
}
