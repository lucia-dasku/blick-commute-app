package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room-persisted commute routine. Deliberately supports N rows from day one (see
 * domain/model/CommuteRoutine.kt) even though the first-version UI only lets a user
 * create one at a time — this was an explicit correction during architecture review:
 * do not model this as a single-row/singleton table.
 *
 * `activeDaysMask` stores [java.time.DayOfWeek] values as a bitmask (bit 0 = MONDAY ...
 * bit 6 = SUNDAY) rather than a comma-joined string, so querying/filtering stays simple
 * and there's no string-parsing edge case for an empty set.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val siteId: Long,
    val siteName: String,
    val transportMode: String,
    val lineId: Long?,
    val lineDesignation: String?,
    val directionCode: Int?,
    val destinationLabel: String?,
    val activeDaysMask: Int,
    val startTimeMinutes: Int,
    val endTimeMinutes: Int,
    val enabled: Boolean,
    val pausedDateEpochDay: Long?,
    val routineType: String = "LINE_DIRECTION",
    val journeyOriginId: String? = null,
    val journeyOriginName: String? = null,
    val journeyDestinationId: String? = null,
    val journeyDestinationName: String? = null,
    val allowedJourneyTransportModes: String = "METRO,TRAIN,BUS,TRAM,FERRY",
    /** See [se.blick.app.domain.model.ExactDestinationChangesPreference]'s own doc. */
    val changesPreference: String = "BOTH",
)
