package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val residentId: Long,
    val residentName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val alertType: String, // EXIT_ZONE, ENTER_ZONE, LOW_BATTERY
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val isAcknowledged: Boolean = false,
    val acknowledgedBy: String? = null
)
