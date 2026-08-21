package se.blick.app.billing

import android.content.SharedPreferences

internal const val DEBUG_PREMIUM_OVERRIDE_AVAILABLE = false

internal fun readDebugPremiumOverride(preferences: SharedPreferences): Boolean = false

internal fun writeDebugPremiumOverride(preferences: SharedPreferences, enabled: Boolean) = Unit
