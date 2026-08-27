package se.blick.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "one_time_events")
data class OneTimeEventEntity(
    @PrimaryKey val id: String,
    val label: String,
    val name: String,
    val originId: String,
    val originName: String,
    val destinationId: String,
    val destinationName: String,
    val eventDateEpochDay: Long,
    val eventTimeMinutes: Int,
    val timeType: String,
    val createdAtEpochMilli: Long,
    val updatedAtEpochMilli: Long,
)
