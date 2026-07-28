package se.blick.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.LocalDate
import java.util.UUID

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
)
