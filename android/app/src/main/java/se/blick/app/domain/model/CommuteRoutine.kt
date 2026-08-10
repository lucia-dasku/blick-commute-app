package se.blick.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDate
import java.util.UUID

enum class RoutineType { LINE_DIRECTION, EXACT_DESTINATION }

/**
 * A saved commute routine. Deliberately platform-neutral identity fields
 * (siteId/lineId/transportMode/directionCode) per docs/api-contract.md §10 — destination
 * text is display-only and is never the sole identity for a direction, since the live
 * departures feed that currently supplies direction options only reflects routes running
 * within its current forecast window (see [se.blick.app.data.repository.DirectionOptionsSource]).
 *
 * The Room-backed schema (see data/local/room/RoutineEntity.kt) supports any number of
 * these, even though the first-version UI only offers creating one at a time.
 */
data class CommuteRoutine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val siteId: Long,
    val siteName: String,
    val transportMode: TransportMode,
    val lineId: Long?,
    val lineDesignation: String?,
    val directionCode: Int?,
    val destinationLabel: String?,
    val activeDays: Set<DayOfWeek>,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val enabled: Boolean = true,
    val pausedDate: LocalDate? = null,
    val type: RoutineType = RoutineType.LINE_DIRECTION,
    /** SL Journey Planner global identifiers. They are intentionally separate from SL
     * Transport's numeric site id and from the display-only direction label. */
    val journeyOriginId: String? = null,
    val journeyOriginName: String? = null,
    val journeyDestinationId: String? = null,
    val journeyDestinationName: String? = null,
    /** Public-transport modes SL may use when planning an exact-destination journey. */
    val allowedJourneyTransportModes: Set<TransportMode> = DEFAULT_JOURNEY_TRANSPORT_MODES,
)
