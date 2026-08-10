package se.blick.app.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import se.blick.app.domain.model.TransportMode
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
    val JOURNEY_FASTEST_DEPARTURE = longPreferencesKey("journeyFastestDeparture")
    val JOURNEY_FASTEST_ARRIVAL = longPreferencesKey("journeyFastestArrival")
    val JOURNEY_FASTEST_CHANGES = longPreferencesKey("journeyFastestChanges")
    val JOURNEY_FASTEST_REALTIME = booleanPreferencesKey("journeyFastestRealtime")
    val JOURNEY_ALTERNATIVE_DEPARTURE = longPreferencesKey("journeyAlternativeDeparture")
    val JOURNEY_ALTERNATIVE_ARRIVAL = longPreferencesKey("journeyAlternativeArrival")
    val JOURNEY_ALTERNATIVE_CHANGES = longPreferencesKey("journeyAlternativeChanges")
    val JOURNEY_ALTERNATIVE_REALTIME = booleanPreferencesKey("journeyAlternativeRealtime")
    val JOURNEY_ALTERNATIVE_LINE = stringPreferencesKey("journeyAlternativeLine")
    val JOURNEY_ALTERNATIVE_MODE = stringPreferencesKey("journeyAlternativeMode")
}

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
                    prefs.writeJourney(content.fastest, alternative = false)
                    content.alternative?.let { prefs.writeJourney(it, alternative = true) }
                }
            }
        }
    }
}

private fun MutablePreferences.writeJourney(row: WidgetJourneyRow, alternative: Boolean) {
    if (!alternative) {
        this[WidgetKeys.JOURNEY_FASTEST_DEPARTURE] = row.departureTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_FASTEST_ARRIVAL] = row.arrivalTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_FASTEST_CHANGES] = row.transferCount.toLong()
        this[WidgetKeys.JOURNEY_FASTEST_REALTIME] = row.isRealtime
    } else {
        this[WidgetKeys.JOURNEY_ALTERNATIVE_DEPARTURE] = row.departureTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_ALTERNATIVE_ARRIVAL] = row.arrivalTime.toEpochMilli()
        this[WidgetKeys.JOURNEY_ALTERNATIVE_CHANGES] = row.transferCount.toLong()
        this[WidgetKeys.JOURNEY_ALTERNATIVE_REALTIME] = row.isRealtime
        row.lineDesignation?.let { this[WidgetKeys.JOURNEY_ALTERNATIVE_LINE] = it }
        this[WidgetKeys.JOURNEY_ALTERNATIVE_MODE] = row.transportMode.name
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
            val fastestDeparture = this[WidgetKeys.JOURNEY_FASTEST_DEPARTURE] ?: return RoutineWidgetUiState.NoActiveCommute
            val fastestArrival = this[WidgetKeys.JOURNEY_FASTEST_ARRIVAL] ?: return RoutineWidgetUiState.NoActiveCommute
            val fastest = WidgetJourneyRow(
                lineDesignation, transportMode, Instant.ofEpochMilli(fastestDeparture), Instant.ofEpochMilli(fastestArrival),
                (this[WidgetKeys.JOURNEY_FASTEST_CHANGES] ?: 0L).toInt(), this[WidgetKeys.JOURNEY_FASTEST_REALTIME] ?: false,
            )
            val alternative = this[WidgetKeys.JOURNEY_ALTERNATIVE_DEPARTURE]?.let { departure ->
                WidgetJourneyRow(
                    this[WidgetKeys.JOURNEY_ALTERNATIVE_LINE],
                    this[WidgetKeys.JOURNEY_ALTERNATIVE_MODE]?.toTransportMode() ?: TransportMode.UNKNOWN,
                    Instant.ofEpochMilli(departure),
                    Instant.ofEpochMilli(this[WidgetKeys.JOURNEY_ALTERNATIVE_ARRIVAL] ?: return@let null),
                    (this[WidgetKeys.JOURNEY_ALTERNATIVE_CHANGES] ?: 0L).toInt(),
                    this[WidgetKeys.JOURNEY_ALTERNATIVE_REALTIME] ?: false,
                )
            }
            RoutineWidgetContent.Journeys(fastest, alternative)
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
        ),
    )
}

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
