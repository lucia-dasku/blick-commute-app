package se.blick.app.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private object Keys {
    val USE_DARK_THEME = booleanPreferencesKey("use_dark_theme")
    val HAS_SET_DARK_THEME = booleanPreferencesKey("has_set_dark_theme")
    val USE_STOCKHOLM_NIGHT_THEME = booleanPreferencesKey("use_stockholm_night_theme")
    val HAS_SEEN_NOTIFICATION_RATIONALE = booleanPreferencesKey("has_seen_notification_rationale")
    val HAS_ACKNOWLEDGED_ATTRIBUTION = booleanPreferencesKey("has_acknowledged_attribution")
}

class PreferencesAppSettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsDataStore {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            useDarkTheme = if (prefs[Keys.HAS_SET_DARK_THEME] == true) prefs[Keys.USE_DARK_THEME] else null,
            useStockholmNightTheme = prefs[Keys.USE_STOCKHOLM_NIGHT_THEME] ?: false,
            hasSeenNotificationRationale = prefs[Keys.HAS_SEEN_NOTIFICATION_RATIONALE] ?: false,
            hasAcknowledgedAttribution = prefs[Keys.HAS_ACKNOWLEDGED_ATTRIBUTION] ?: false,
        )
    }

    override suspend fun setUseDarkTheme(useDarkTheme: Boolean?) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_SET_DARK_THEME] = useDarkTheme != null
            if (useDarkTheme != null) prefs[Keys.USE_DARK_THEME] = useDarkTheme
        }
    }

    override suspend fun setUseStockholmNightTheme(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.USE_STOCKHOLM_NIGHT_THEME] = enabled }
    }

    override suspend fun setHasSeenNotificationRationale(seen: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.HAS_SEEN_NOTIFICATION_RATIONALE] = seen }
    }

    override suspend fun setHasAcknowledgedAttribution(acknowledged: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.HAS_ACKNOWLEDGED_ATTRIBUTION] = acknowledged }
    }
}
