package se.blick.app.data.local.datastore

/**
 * Small app-level settings only — see docs/api-contract.md and the architecture
 * decision to use Preferences DataStore exclusively for this, never for routine data
 * (routines live in Room, see data/local/room). Deliberately does not include an
 * account identifier, GPS permission flag, or any advertising/analytics identifier,
 * per the MVP's privacy principles.
 */
data class AppSettings(
    val useDarkTheme: Boolean? = null, // null = follow system
    val hasSeenNotificationRationale: Boolean = false,
    val hasAcknowledgedAttribution: Boolean = false,
)
