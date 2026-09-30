package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "residents")
data class Resident(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val roomNumber: String = "",
    val photoUri: String? = null,
    val avatarColorHex: String = "#1E88E5",
    val weenectUsername: String = "",
    val weenectPassword: String = "",
    val trackerId: Long? = null,
    val trackerName: String? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastBattery: Int? = null,
    val lastSpeed: Double? = null,
    val lastUpdatedTime: Long? = null,
    val isInZone: Boolean = true,
    val distanceFromCenterMeters: Double = 0.0,
    val emergencyContact: String = "",
    val notes: String = "",
    val isTrackingActive: Boolean = true
)
