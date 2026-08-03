package se.blick.app.data.remote

import se.blick.app.data.remote.dto.DepartureDto
import se.blick.app.data.remote.dto.DeparturesResponseDto
import se.blick.app.data.remote.dto.DisruptionDto
import se.blick.app.data.remote.dto.DisruptionsResponseDto
import se.blick.app.data.remote.dto.SiteDto
import se.blick.app.domain.model.Departure
import se.blick.app.domain.model.DeparturesResult
import se.blick.app.domain.model.Disruption
import se.blick.app.domain.model.DisruptionAffectedLine
import se.blick.app.domain.model.DisruptionMessage
import se.blick.app.domain.model.DisruptionPriority
import se.blick.app.domain.model.Journey
import se.blick.app.domain.model.LineRef
import se.blick.app.domain.model.Site
import se.blick.app.domain.model.SiteDeviationStopAreaRef
import se.blick.app.domain.model.StopAreaRef
import se.blick.app.domain.model.StopPointRef
import se.blick.app.domain.model.TripDeviation
import se.blick.app.domain.model.toTransportMode
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Every timestamp crossing the data-layer boundary is parsed here, exactly once, into
 * [Instant] — per the architecture decision that domain models never hold a raw
 * timestamp string. All backend timestamps are explicit-offset ISO 8601
 * (docs/api-contract.md §5), so [OffsetDateTime.parse] is always correct; there is no
 * naive-local-time ambiguity to resolve on the Android side — that resolution already
 * happened server-side.
 */
private fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

fun SiteDto.toDomain(): Site = Site(
    siteId = siteId,
    name = name,
    note = note,
    lat = lat,
    lon = lon,
    stopAreaIds = stopAreaIds,
)

fun DepartureDto.toDomain(): Departure = Departure(
    departureId = departureId,
    line = LineRef(line.id, line.designation, line.transportMode.toTransportMode()),
    direction = direction,
    directionCode = directionCode,
    destination = destination,
    via = via,
    stopArea = StopAreaRef(stopArea.id, stopArea.name, stopArea.type),
    stopPoint = StopPointRef(stopPoint.id, stopPoint.name, stopPoint.designation),
    scheduledTime = scheduledTime.toInstant(),
    expectedTime = expectedTime?.toInstant(),
    state = state,
    isCancelled = isCancelled,
    journey = Journey(journey.id, journey.state, journey.predictionState),
    tripDeviations = tripDeviations.map { TripDeviation(it.importanceLevel, it.consequence, it.message) },
)

fun DeparturesResponseDto.toDomain(): DeparturesResult = DeparturesResult(
    fetchedAt = fetchedAt.toInstant(),
    siteId = siteId,
    departures = departures.map { it.toDomain() },
)

fun DisruptionDto.toDomain(): Disruption = Disruption(
    disruptionId = disruptionId,
    version = version,
    createdAt = createdAt.toInstant(),
    modifiedAt = modifiedAt?.toInstant(),
    validFrom = validFrom?.toInstant(),
    validUntil = validUntil?.toInstant(),
    priority = DisruptionPriority(priority.importance, priority.influence, priority.urgency),
    message = DisruptionMessage(message.header, message.details, message.scopeAlias, message.webLink, message.language),
    affectedStopAreas = affectedStopAreas.map { SiteDeviationStopAreaRef(it.id, it.name, it.type) },
    affectedLines = affectedLines.map {
        DisruptionAffectedLine(it.id, it.designation, it.transportMode.toTransportMode(), it.name)
    },
    affectedModes = affectedModes.map { it.toTransportMode() },
)

fun DisruptionsResponseDto.toDomain(): List<Disruption> = disruptions.map { it.toDomain() }
