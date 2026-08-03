package se.blick.app.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val RECOVERY_PENDING = booleanPreferencesKey("notification_recovery_pending")

/**
 * Durable "is a notification-recovery attempt still owed" flag — the one piece of state
 * [se.blick.app.scheduling.NotificationRecoveryCoordinator] (see that class's own doc) needs to
 * survive process recreation. Deliberately a plain sticky boolean, not a last-known-availability
 * snapshot compared across calls: every real detector of "notifications are NOT available right
 * now" ([se.blick.app.scheduling.RoutineActiveWindowWorker] before it stops,
 * `RoutineDetailsViewModel`'s own availability checks, and the coordinator's own startup/foreground
 * checks) durably marks this `true` the moment it observes that — and it is deliberately NOT
 * cleared just because availability is later observed as available again; only a fully
 * successful recovery attempt (every routine that needed (re)scheduling actually got scheduled,
 * and the widget was reconciled) clears it. If Room, DataStore, or WorkManager fails partway
 * through a recovery attempt, this stays `true` so the next foreground/startup check retries —
 * see [se.blick.app.scheduling.NotificationRecoveryCoordinator]'s own doc for exactly where that
 * retry boundary is.
 *
 * Defaults to `false` when nothing has ever been written (a fresh install, or before this
 * store's first-ever write) — there is no "transition" concept here to get wrong on first cold
 * start (unlike the boolean-transition design this store replaces): a fresh install simply has
 * nothing pending, and the app's own cold-start reconciliation
 * ([se.blick.app.scheduling.NotificationRecoveryCoordinator.onAppStart]) already unconditionally
 * covers every enabled routine regardless of this flag's value.
 *
 * A plain in-memory field (like a ViewModel's own state) cannot serve this purpose: it resets
 * every time the process is recreated, which is exactly when this matters most — Blick
 * backgrounded, notifications toggled in system Settings, the process killed by the OS in the
 * meantime, then reopened. Deliberately its own small store rather than folded into
 * [se.blick.app.data.local.datastore.AppSettings] — that model's own doc is explicit that it
 * holds "small app-level settings only"; this is internal scheduling-recovery bookkeeping, not a
 * user-facing setting, even though it happens to share the same underlying [DataStore] instance.
 */
interface RecoveryPendingStateStore {
    val recoveryPending: Flow<Boolean>
    suspend fun markRecoveryPending()
    suspend fun clearRecoveryPending()
}

class PreferencesRecoveryPendingStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RecoveryPendingStateStore {

    override val recoveryPending: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[RECOVERY_PENDING] ?: false }

    override suspend fun markRecoveryPending() {
        dataStore.edit { prefs -> prefs[RECOVERY_PENDING] = true }
    }

    override suspend fun clearRecoveryPending() {
        dataStore.edit { prefs -> prefs[RECOVERY_PENDING] = false }
    }
}
