package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "facility_zone")
data class FacilityZone(
    @PrimaryKey
    val id: Int = 1,
    val name: String = "MAS l'Épeau",
    val centerLatitude: Double = 48.8566,
    val centerLongitude: Double = 2.3522,
    val radiusMeters: Double = 150.0,
    val isZoneActive: Boolean = true,
    val soundAlertsEnabled: Boolean = true,
    val vibrateAlertsEnabled: Boolean = true,
    val refreshIntervalSeconds: Int = 15
)
