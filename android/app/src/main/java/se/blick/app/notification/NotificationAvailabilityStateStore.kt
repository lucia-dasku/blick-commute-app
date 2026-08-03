package se.blick.app.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val LAST_KNOWN_NOTIFICATIONS_AVAILABLE = booleanPreferencesKey("last_known_notifications_available")

/**
 * Persists the last-known [NotificationAvailability.Available]/not-available snapshot across
 * process recreation — the one piece of state [se.blick.app.scheduling.ForegroundNotificationRecovery]
 * (see that class's own doc) needs to detect a genuine unavailable-to-available TRANSITION on
 * app foreground, rather than treating every single foreground as a reason to reconcile
 * scheduling regardless of whether anything actually changed (the bug that class exists to
 * fix). A plain in-memory field (like `RoutineDetailsViewModel`'s own
 * `notificationAvailability` state) cannot serve this purpose: it resets every time the process
 * is recreated, which is exactly when this check matters most — Blick backgrounded,
 * notifications toggled in system Settings, the process killed by the OS in the meantime, then
 * reopened.
 *
 * Deliberately its own small store rather than folded into [se.blick.app.data.local.datastore.AppSettings]
 * — that model's own doc is explicit that it holds "small app-level settings only", enumerating
 * exactly what belongs there; this is internal scheduling-recovery bookkeeping, not a user-facing
 * setting, so it gets its own single-purpose home even though it happens to share the same
 * underlying [DataStore] instance.
 *
 * [lastKnownAvailable] is `null` only when nothing has ever been recorded (a fresh install, or
 * before this store's very first write) — [se.blick.app.scheduling.ForegroundNotificationRecovery]
 * treats that as "no transition" rather than as an unavailable-to-available transition, since the
 * app's own cold-start reconciliation (see `BlickApplication.onCreate`) already covers that case.
 */
interface NotificationAvailabilityStateStore {
    val lastKnownAvailable: Flow<Boolean?>
    suspend fun setLastKnownAvailable(available: Boolean)
}

class PreferencesNotificationAvailabilityStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : NotificationAvailabilityStateStore {

    override val lastKnownAvailable: Flow<Boolean?> =
        dataStore.data.map { prefs -> prefs[LAST_KNOWN_NOTIFICATIONS_AVAILABLE] }

    override suspend fun setLastKnownAvailable(available: Boolean) {
        dataStore.edit { prefs -> prefs[LAST_KNOWN_NOTIFICATIONS_AVAILABLE] = available }
    }
}
