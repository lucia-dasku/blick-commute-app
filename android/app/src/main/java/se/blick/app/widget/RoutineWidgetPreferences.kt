package se.blick.app.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import se.blick.app.domain.model.ExactDestinationChangesPreference
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import se.blick.app.domain.model.toExactDestinationChangesPreference
import se.blick.app.domain.model.toJourneyRole
import se.blick.app.domain.model.toTransportMode
import java.time.Instant

/**
 * The [Preferences] shape [RoutineWidgetUiState] is persisted as, via Glance's built-in
 * `PreferencesGlanceStateDefinition` (one such [Preferences] instance per widget instance/
 * [androidx.glance.GlanceId], entirely managed by Glance/DataStore — see [BlickRoutineWidget]).
 * Kept as pure, Android/Glance-independent read/write functions so the encode/decode logic is
 * unit-testable on plain [Preferences]/[MutablePreferences] values without any widget instance,
 * [android.content.Context], or Robolectric involved.
 */
private object WidgetKeys {
    val CONTENT_TYPE = stringPreferencesKey("contentType")
    val ROUTINE_ID = stringPreferencesKey("routineId")
    val ROUTINE_NAME = stringPreferencesKey("routineName")
    val STATION_NAME = stringPreferencesKey("stationName")
    val DIRECTION_LABEL = stringPreferencesKey("directionLabel")
    val LINE_DESIGNATION = stringPreferencesKey("lineDesignation")
    val TRANSPORT_MODE = stringPreferencesKey("transportMode")
    val DISRUPTION_HEADLINE = stringPreferencesKey("disruptionHeadline")
    /** Encoded via [encodeLineDesignations] (see that function's own doc) — new in this version;
     * absent entirely on state persisted by an older app version, or whenever the current
     * disruption (if any) is `CONFIRMED`/`LINE_DIRECTION` rather than `LINE_RELEVANT`. */
    val DISRUPTION_UNCERTAIN_LINE_DESIGNATIONS = stringPreferencesKey("disruptionUncertainLineDesignations")
    val LAST_CHECKED_AT_EPOCH_MILLIS = longPreferencesKey("lastCheckedAtEpochMillis")
    val NEXT_LINE = stringPreferencesKey("nextLine")
    val NEXT_DESTINATION = stringPreferencesKey("nextDestination")
    val NEXT_MINUTES = longPreferencesKey("nextMinutes")
    val NEXT_IS_REAL_TIME = booleanPreferencesKey("nextIsRealTime")
    val NEXT_IS_CANCELLED = booleanPreferencesKey("nextIsCancelled")
    val FOLLOWING_LINE = stringPreferencesKey("followingLine")
    val FOLLOWING_DESTINATION = stringPreferencesKey("followingDestination")
    val FOLLOWING_MINUTES = longPreferencesKey("followingMinutes")
    val FOLLOWING_IS_REAL_TIME = booleanPreferencesKey("followingIsRealTime")
    val FOLLOWING_IS_CANCELLED = booleanPreferencesKey("followingIsCancelled")
    // Kotlin identifiers renamed to match RoutineWidgetContent.Journeys's own primary/secondary
    // naming (see that class's own doc); the underlying string literals are left exactly as
    // they were so a widget instance's already-persisted state keeps decoding correctly
    // across this app update rather than silently reverting to NoActiveCommute for one cycle.
    val JOURNEY_PRIMARY_DEPARTURE = longPreferencesKey("journeyFastestDeparture")
    val JOURNEY_PRIMARY_ARRIVAL = longPreferencesKey("journeyFastestArrival")
    val JOURNEY_PRIMARY_CHANGES = longPreferencesKey("journeyFastestChanges")
    val JOURNEY_PRIMARY_REALTIME = booleanPreferencesKey("journeyFastestRealtime")
    /** New in this version — absent entirely on state persisted by an older app version; see
     * [readJourneyRole]'s own doc for how that's handled on read. */
    val JOURNEY_PRIMARY_ROLE = stringPreferencesKey("journeyPrimaryRole")
    val JOURNEY_SECONDARY_DEPARTURE = longPreferencesKey("journeyAlternativeDeparture")
    val JOURNEY_SECONDARY_ARRIVAL = longPreferencesKey("journeyAlternativeArrival")
    val JOURNEY_SECONDARY_CHANGES = longPreferencesKey("journeyAlternativeChanges")
    val JOURNEY_SECONDARY_REALTIME = booleanPreferencesKey("journeyAlternativeRealtime")
    val JOURNEY_SECONDARY_LINE = stringPreferencesKey("journeyAlternativeLine")
    val JOURNEY_SECONDARY_MODE = stringPreferencesKey("journeyAlternativeMode")
    /** New in this version — see [JOURNEY_PRIMARY_ROLE]'s own doc. */
    val JOURNEY_SECONDARY_ROLE = stringPreferencesKey("journeySecondaryRole")
    /** New in this version — absent entirely on state persisted by an older app version; see
     * [se.blick.app.domain.model.toExactDestinationChangesPreference]'s own
     * [ExactDestinationChangesPreference.BOTH] fallback for how that's handled on read. */
    val JOURNEY_CHANGES_PREFERENCE = stringPreferencesKey("journeyChangesPreference")
    /** Encoded per-leg line badges (see [encodeLegBadges]'s own doc) — new in this version;
     * absent entirely on state persisted by an older app version, or for a `Journeys` row that
     * genuinely has none. */
    val JOURNEY_PRIMARY_LEG_BADGES = stringPreferencesKey("journeyPrimaryLegBadges")
    val JOURNEY_SECONDARY_LEG_BADGES = stringPreferencesKey("journeySecondaryLegBadges")
}

/** Encodes [this] as `"14:METRO|40:BUS"` — pipe-separated leg entries, each a colon-separated
 * `lineDesignation:transportMode` pair, in journey order. [Preferences] has no native list/struct
 * type (only flat primitives, plus an unordered `Set<String>` unsuited to this ordered,
 * paired data — see [androidx.datastore.preferences.core.stringSetPreferencesKey]), so this
 * mirrors [se.blick.app.data.local.room.RoutineMappers.kt]'s own comma-joined
 * `allowedJourneyTransportModes` encoding: a real SL line designation never itself contains `:`
 * or `|`, exactly like a [TransportMode] enum name never contains a comma. */
private fun List<WidgetJourneyLegBadge>.encodeLegBadges(): String = joinToString("|") { "${it.lineDesignation}:${it.transportMode.name}" }

/** The exact inverse of [encodeLegBadges] — a null/blank/malformed value (absent key, a version
 * predating this field, or unlikely datastore corruption) decodes to an empty list rather than
 * throwing; [legBadgesOrFallback] is what render time falls back to a single header-derived badge
 * with in that case, never zero badges for a journey that plainly has a line. */
private fun String?.decodeLegBadges(): List<WidgetJourneyLegBadge> =
    this?.takeIf(String::isNotEmpty)
        ?.split("|")
        ?.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2 || parts[0].isEmpty()) null else WidgetJourneyLegBadge(parts[0], parts[1].toTransportMode())
        }
        .orEmpty()

/** Encodes [RoutineWidgetModel.disruptionUncertainLineDesignations] as `"11|17"` — pipe-separated,
 * matching [encodeLegBadges]'s own convention (a real SL line designation never itself contains
 * `|`). */
private fun List<String>.encodeLineDesignations(): String = joinToString("|")

/** The exact inverse of [encodeLineDesignations] — a null/blank value (absent key, or a version
 * predating this field) decodes to an empty list, matching [RoutineWidgetModel.disruptionUncertainLineDesignations]'s
 * own default. */
private fun String?.decodeLineDesignations(): List<String> = this?.takeIf(String::isNotEmpty)?.split("|").orEmpty()

private enum class ContentType { NO_ACTIVE_COMMUTE, LOADING, LIVE, STALE, NO_UPCOMING, OFFLINE, UNAVAILABLE, NOTIFICATIONS_UNAVAILABLE, JOURNEYS }

/** Clears every key this codec owns before writing new ones — a widget state transition (e.g.
 * [RoutineWidgetContent.Live] to [RoutineWidgetContent.Offline]) must never leave a stale
 * `nextLine`/`nextMinutes` from a previous state lingering unread-but-present in the datastore. */
internal fun RoutineWidgetUiState.writeInto(prefs: MutablePreferences) {
    prefs.clear()
    when (this) {
        RoutineWidgetUiState.NoActiveCommute -> prefs[WidgetKeys.CONTENT_TYPE] = ContentType.NO_ACTIVE_COMMUTE.name
        is RoutineWidgetUiState.ActiveRoutine -> {
            prefs[WidgetKeys.ROUTINE_ID] = model.routineId
            prefs[WidgetKeys.ROUTINE_NAME] = model.routineName
            prefs[WidgetKeys.STATION_NAME] = model.stationName
            prefs[WidgetKeys.TRANSPORT_MODE] = model.transportMode.name
            model.directionLabel?.let { prefs[WidgetKeys.DIRECTION_LABEL] = it }
            model.lineDesignation?.let { prefs[WidgetKeys.LINE_DESIGNATION] = it }
            model.disruptionHeadline?.let { prefs[WidgetKeys.DISRUPTION_HEADLINE] = it }
            if (model.disruptionUncertainLineDesignations.isNotEmpty()) {
                prefs[WidgetKeys.DISRUPTION_UNCERTAIN_LINE_DESIGNATIONS] = model.disruptionUncertainLineDesignations.encodeLineDesignations()
            }
            when (val content = model.content) {
                RoutineWidgetContent.Loading -> prefs[WidgetKeys.CONTENT_TYPE] = ContentType.LOADING.name
                is RoutineWidgetContent.Live -> {
                    prefs[WidgetKeys.CONTENT_TYPE] = ContentType.LIVE.name
                    prefs.writeNext(content.next)
                    content.following?.let { prefs.writeFollowing(it) }
                }
                is RoutineWidgetContent.Stale -> {
                    prefs[WidgetKeys.CONTENT_TYPE] = ContentType.STALE.name
                    prefs[WidgetKeys.LAST_CHECKED_AT_EPOCH_MILLIS] = content.lastCheckedAt.toEpochMilli()
                    content.next?.let { prefs.writeNext(it) }
                    content.following?.let { prefs.writeFollowing(it) }
                }
                is RoutineWidgetContent.NoUpcomingDepartures -> {
                    prefs[WidgetKeys.CONTENT_TYPE] = ContentType.NO_UPCOMING.name
                    prefs[WidgetKeys.LAST_CHECKED_AT_EPOCH_MILLIS] = content.lastCheckedAt.toEpochMilli()
                }
                RoutineWidgetContent.Offline -> prefs[WidgetKeys.CONTENT_TYPE] = ContentType.OFFLINE.name
                RoutineWidgetContent.Unavailable -> prefs[WidgetKeys.CONTENT_TYPE] = ContentType.UNAVAILABLE.name
                RoutineWidgetContent.NotificationsUnavailable ->
                    prefs[WidgetKeys.CONTENT_TYPE] = ContentType.NOTIFICATIONS_UNAVAILABLE.name
                is RoutineWidgetContent.Journeys -> {
                    prefs[WidgetKeys.CONTENT_TYPE] = ContentType.JOURNEYS.name
                    prefs[WidgetKeys.JOURNEY_CHANGES_PREFERENCE] = content.changesPreference.name
                    prefs.writeJourney(content.primary, isSecondary = false)
                    content.secondary?.let { prefs.writeJourney(it, isSecondary = true) }
                }
            }
        }
    }
}

private fun MutablePreferences.writeJourney(row: WidgetJourneyRow, isSecondary: Boolean) {
    if (!isSecondary) {
        this[WidgetKeys.JOURNEY_PRIMARY_DEPARTURE] = row.departureTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_PRIMARY_ARRIVAL] = row.arrivalTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_PRIMARY_CHANGES] = row.transferCount.toLong()
        this[WidgetKeys.JOURNEY_PRIMARY_REALTIME] = row.isRealtime
        this[WidgetKeys.JOURNEY_PRIMARY_ROLE] = row.role.name
        if (row.legBadges.isNotEmpty()) this[WidgetKeys.JOURNEY_PRIMARY_LEG_BADGES] = row.legBadges.encodeLegBadges()
    } else {
        this[WidgetKeys.JOURNEY_SECONDARY_DEPARTURE] = row.departureTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_SECONDARY_ARRIVAL] = row.arrivalTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_SECONDARY_CHANGES] = row.transferCount.toLong()
        this[WidgetKeys.JOURNEY_SECONDARY_REALTIME] = row.isRealtime
        row.lineDesignation?.let { this[WidgetKeys.JOURNEY_SECONDARY_LINE] = it }
        this[WidgetKeys.JOURNEY_SECONDARY_MODE] = row.transportMode.name
        this[WidgetKeys.JOURNEY_SECONDARY_ROLE] = row.role.name
        if (row.legBadges.isNotEmpty()) this[WidgetKeys.JOURNEY_SECONDARY_LEG_BADGES] = row.legBadges.encodeLegBadges()
    }
}

private fun MutablePreferences.writeNext(row: WidgetDepartureRow) {
    this[WidgetKeys.NEXT_LINE] = row.lineDesignation
    row.destinationLabel?.let { this[WidgetKeys.NEXT_DESTINATION] = it }
    this[WidgetKeys.NEXT_MINUTES] = row.minutesRemaining
    this[WidgetKeys.NEXT_IS_REAL_TIME] = row.isRealTime
    this[WidgetKeys.NEXT_IS_CANCELLED] = row.isCancelled
}

private fun MutablePreferences.writeFollowing(row: WidgetDepartureRow) {
    this[WidgetKeys.FOLLOWING_LINE] = row.lineDesignation
    row.destinationLabel?.let { this[WidgetKeys.FOLLOWING_DESTINATION] = it }
    this[WidgetKeys.FOLLOWING_MINUTES] = row.minutesRemaining
    this[WidgetKeys.FOLLOWING_IS_REAL_TIME] = row.isRealTime
    this[WidgetKeys.FOLLOWING_IS_CANCELLED] = row.isCancelled
}

/** The exact inverse of [writeInto] — reconstructs a [RoutineWidgetUiState] from whatever is
 * currently persisted, defaulting to [RoutineWidgetUiState.NoActiveCommute] for an empty/unknown
 * (e.g. a freshly-placed widget instance that hasn't received its first update yet) state. */
internal fun Preferences.toWidgetUiState(): RoutineWidgetUiState {
    val contentType = this[WidgetKeys.CONTENT_TYPE]?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
        ?: return RoutineWidgetUiState.NoActiveCommute
    if (contentType == ContentType.NO_ACTIVE_COMMUTE) return RoutineWidgetUiState.NoActiveCommute

    val routineId = this[WidgetKeys.ROUTINE_ID] ?: return RoutineWidgetUiState.NoActiveCommute
    val routineName = this[WidgetKeys.ROUTINE_NAME].orEmpty()
    val stationName = this[WidgetKeys.STATION_NAME].orEmpty()
    val directionLabel = this[WidgetKeys.DIRECTION_LABEL]
    val lineDesignation = this[WidgetKeys.LINE_DESIGNATION]
    val disruptionHeadline = this[WidgetKeys.DISRUPTION_HEADLINE]
    val disruptionUncertainLineDesignations = this[WidgetKeys.DISRUPTION_UNCERTAIN_LINE_DESIGNATIONS].decodeLineDesignations()
    // .toTransportMode() defaults to TransportMode.UNKNOWN both for a genuinely unrecognized
    // value and for a widget instance whose prefs were written by an app version before this
    // key existed (this[...] is then simply null) -- either way, a safe grey badge, never a
    // decode failure.
    val transportMode = this[WidgetKeys.TRANSPORT_MODE]?.toTransportMode() ?: TransportMode.UNKNOWN
    val next = readNext()
    val following = readFollowing()
    val lastCheckedAt = this[WidgetKeys.LAST_CHECKED_AT_EPOCH_MILLIS]?.let { Instant.ofEpochMilli(it) }

    val content = when (contentType) {
        ContentType.LOADING -> RoutineWidgetContent.Loading
        ContentType.LIVE -> next?.let { RoutineWidgetContent.Live(next = it, following = following) } ?: RoutineWidgetContent.Loading
        ContentType.STALE -> RoutineWidgetContent.Stale(next = next, following = following, lastCheckedAt = lastCheckedAt ?: Instant.EPOCH)
        ContentType.NO_UPCOMING -> RoutineWidgetContent.NoUpcomingDepartures(lastCheckedAt = lastCheckedAt ?: Instant.EPOCH)
        ContentType.OFFLINE -> RoutineWidgetContent.Offline
        ContentType.UNAVAILABLE -> RoutineWidgetContent.Unavailable
        ContentType.NOTIFICATIONS_UNAVAILABLE -> RoutineWidgetContent.NotificationsUnavailable
        ContentType.JOURNEYS -> {
            val primaryDeparture = this[WidgetKeys.JOURNEY_PRIMARY_DEPARTURE] ?: return RoutineWidgetUiState.NoActiveCommute
            val primaryArrival = this[WidgetKeys.JOURNEY_PRIMARY_ARRIVAL] ?: return RoutineWidgetUiState.NoActiveCommute
            val primary = WidgetJourneyRow(
                lineDesignation, transportMode, Instant.ofEpochMilli(primaryDeparture), Instant.ofEpochMilli(primaryArrival),
                (this[WidgetKeys.JOURNEY_PRIMARY_CHANGES] ?: 0L).toInt(), this[WidgetKeys.JOURNEY_PRIMARY_REALTIME] ?: false,
                readJourneyRole(WidgetKeys.JOURNEY_PRIMARY_ROLE, default = JourneyRole.PRIMARY),
                this[WidgetKeys.JOURNEY_PRIMARY_LEG_BADGES].decodeLegBadges(),
            )
            val secondary = this[WidgetKeys.JOURNEY_SECONDARY_DEPARTURE]?.let { departure ->
                WidgetJourneyRow(
                    this[WidgetKeys.JOURNEY_SECONDARY_LINE],
                    this[WidgetKeys.JOURNEY_SECONDARY_MODE]?.toTransportMode() ?: TransportMode.UNKNOWN,
                    Instant.ofEpochMilli(departure),
                    Instant.ofEpochMilli(this[WidgetKeys.JOURNEY_SECONDARY_ARRIVAL] ?: return@let null),
                    (this[WidgetKeys.JOURNEY_SECONDARY_CHANGES] ?: 0L).toInt(),
                    this[WidgetKeys.JOURNEY_SECONDARY_REALTIME] ?: false,
                    // NEXT, not ALTERNATIVE, is the more common real outcome of the backend's
                    // own selection algorithm -- the safer default for the rare case of a
                    // secondary row persisted by a version predating this field.
                    readJourneyRole(WidgetKeys.JOURNEY_SECONDARY_ROLE, default = JourneyRole.NEXT),
                    this[WidgetKeys.JOURNEY_SECONDARY_LEG_BADGES].decodeLegBadges(),
                )
            }
            RoutineWidgetContent.Journeys(primary, secondary, this[WidgetKeys.JOURNEY_CHANGES_PREFERENCE].toExactDestinationChangesPreference())
        }
        ContentType.NO_ACTIVE_COMMUTE -> return RoutineWidgetUiState.NoActiveCommute
    }
    return RoutineWidgetUiState.ActiveRoutine(
        RoutineWidgetModel(
            routineId = routineId,
            routineName = routineName,
            stationName = stationName,
            directionLabel = directionLabel,
            content = content,
            lineDesignation = lineDesignation,
            transportMode = transportMode,
            disruptionHeadline = disruptionHeadline,
            disruptionUncertainLineDesignations = disruptionUncertainLineDesignations,
        ),
    )
}

/** Widget state is transient, best-effort, render-only data — unlike
 * [se.blick.app.data.repository.RemoteJourneyRepository]'s own fail-closed
 * [se.blick.app.domain.model.toJourneyRole] (which drops a whole journey rather than ever
 * inventing a role for a live API response), a missing or malformed role in ALREADY-PERSISTED
 * widget state is not a live correctness concern: it only happens for state written by an app
 * version predating this field, or in the unlikely event of datastore corruption, and the
 * worker's own ~30-second refresh loop overwrites it with a freshly role-tagged value again
 * almost immediately regardless. Falls back to [default] rather than dropping the row
 * entirely, which would otherwise regress an upgrading user straight to
 * [RoutineWidgetUiState.NoActiveCommute] for one refresh cycle over what is, at worst, a
 * cosmetic NEXT/ALTERNATIVE wording mismatch (see [BlickRoutineWidget]'s own rendering). */
private fun Preferences.readJourneyRole(key: Preferences.Key<String>, default: JourneyRole): JourneyRole =
    this[key].toJourneyRole() ?: default

private fun Preferences.readNext(): WidgetDepartureRow? {
    val line = this[WidgetKeys.NEXT_LINE] ?: return null
    val minutes = this[WidgetKeys.NEXT_MINUTES] ?: return null
    return WidgetDepartureRow(
        lineDesignation = line,
        destinationLabel = this[WidgetKeys.NEXT_DESTINATION],
        minutesRemaining = minutes,
        isRealTime = this[WidgetKeys.NEXT_IS_REAL_TIME] ?: false,
        isCancelled = this[WidgetKeys.NEXT_IS_CANCELLED] ?: false,
    )
}

private fun Preferences.readFollowing(): WidgetDepartureRow? {
    val line = this[WidgetKeys.FOLLOWING_LINE] ?: return null
    val minutes = this[WidgetKeys.FOLLOWING_MINUTES] ?: return null
    return WidgetDepartureRow(
        lineDesignation = line,
        destinationLabel = this[WidgetKeys.FOLLOWING_DESTINATION],
        minutesRemaining = minutes,
        isRealTime = this[WidgetKeys.FOLLOWING_IS_REAL_TIME] ?: false,
        isCancelled = this[WidgetKeys.FOLLOWING_IS_CANCELLED] ?: false,
    )
}
