package se.blick.app.data.local.room

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.blick.app.domain.model.toJourneyRole
import se.blick.app.domain.model.toTransportMode
import se.blick.app.domain.usecase.DepartureIdentity
import se.blick.app.domain.usecase.LiveDeparturesSnapshot
import se.blick.app.domain.usecase.PreparedDeparture
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

/**
 * Plain-old-data mirror of [PreparedDeparture] for JSON persistence in
 * [StaleSnapshotEntity.departuresJson] — kept separate from the domain model (matching this
 * codebase's DTO/domain split elsewhere, e.g. `data/remote/dto`) rather than annotating
 * [PreparedDeparture] itself with `@Serializable`. [PreparedDeparture.tripDeviations] is
 * deliberately dropped here: no current caller of [PreparedDeparture] actually reads it (see
 * that class's own callers), and disruption detail from a snapshot old enough to have survived
 * a process death would itself already be stale.
 */
@Serializable
private data class StaleDepartureRow(
    val departureId: String,
    val lineDesignation: String,
    val direction: String?,
    val destination: String?,
    val scheduledTimeEpochMilli: Long,
    val expectedTimeEpochMilli: Long?,
    val effectiveTimeEpochMilli: Long,
    val minutesRemaining: Long,
    val isRealTime: Boolean,
    val isCancelled: Boolean,
    val state: String,
    val journeyState: String,
    val predictionState: String?,
    /** Backend-authoritative (see [se.blick.app.domain.model.JourneyRole]'s own doc) --
     * populated only for the exact-destination journey path; always `null` for an ordinary
     * LINE_DIRECTION row, which has no such concept. New in this version: the default keeps
     * decoding a row persisted by an OLDER app version (which never wrote this key at all)
     * safe -- kotlinx.serialization fills a missing key with its declared default rather
     * than failing, so an old snapshot still loads, simply without a role (see `toDomain`'s
     * own fail-closed parse for why that never becomes PRIMARY by accident either). */
    val journeyRole: String? = null,
)

private fun PreparedDeparture.toRow() = StaleDepartureRow(
    departureId = departureId,
    lineDesignation = lineDesignation,
    direction = direction,
    destination = destination,
    scheduledTimeEpochMilli = scheduledTime.toEpochMilli(),
    expectedTimeEpochMilli = expectedTime?.toEpochMilli(),
    effectiveTimeEpochMilli = effectiveTime.toEpochMilli(),
    minutesRemaining = minutesRemaining,
    isRealTime = isRealTime,
    isCancelled = isCancelled,
    state = state,
    journeyState = journeyState,
    predictionState = predictionState,
    journeyRole = journeyRole?.name,
)

private fun StaleDepartureRow.toDomain() = PreparedDeparture(
    departureId = departureId,
    lineDesignation = lineDesignation,
    direction = direction,
    destination = destination,
    scheduledTime = Instant.ofEpochMilli(scheduledTimeEpochMilli),
    expectedTime = expectedTimeEpochMilli?.let(Instant::ofEpochMilli),
    effectiveTime = Instant.ofEpochMilli(effectiveTimeEpochMilli),
    minutesRemaining = minutesRemaining,
    isRealTime = isRealTime,
    isCancelled = isCancelled,
    state = state,
    journeyState = journeyState,
    predictionState = predictionState,
    tripDeviations = emptyList(),
    // Fail-closed (see toJourneyRole's own doc): a missing key (an old snapshot) or a
    // malformed/unrecognized stored value both resolve to null here, never PRIMARY by
    // default -- RoutineNotificationBuilder's own null handling already renders that
    // correctly as the ordinary NEXT wording rather than crashing or guessing ALTERNATIVE.
    journeyRole = journeyRole.toJourneyRole(),
)

fun StaleSnapshotEntity.identity(): DepartureIdentity = DepartureIdentity(
    siteId = siteId,
    lineId = lineId,
    directionCode = directionCode,
    transportMode = transportMode.toTransportMode(),
)

fun StaleSnapshotEntity.toSnapshot(): LiveDeparturesSnapshot = LiveDeparturesSnapshot(
    departures = json.decodeFromString<List<StaleDepartureRow>>(departuresJson).map { it.toDomain() },
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMilli),
)

fun toStaleSnapshotEntity(
    routineId: String,
    identity: DepartureIdentity,
    snapshot: LiveDeparturesSnapshot,
): StaleSnapshotEntity = StaleSnapshotEntity(
    routineId = routineId,
    siteId = identity.siteId,
    lineId = identity.lineId,
    directionCode = identity.directionCode,
    transportMode = identity.transportMode.name,
    fetchedAtEpochMilli = snapshot.fetchedAt.toEpochMilli(),
    departuresJson = json.encodeToString(snapshot.departures.map { it.toRow() }),
)
