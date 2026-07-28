package se.blick.app.data.local.datastore

import kotlinx.coroutines.flow.Flow

interface AppSettingsDataStore {
    val settings: Flow<AppSettings>
    suspend fun setUseDarkTheme(useDarkTheme: Boolean?)
    suspend fun setHasSeenNotificationRationale(seen: Boolean)
    suspend fun setHasAcknowledgedAttribution(acknowledged: Boolean)
}
