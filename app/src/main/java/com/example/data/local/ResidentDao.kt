package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Resident
import kotlinx.coroutines.flow.Flow

@Dao
interface ResidentDao {
    @Query("SELECT * FROM residents ORDER BY name ASC")
    fun getAllResidents(): Flow<List<Resident>>

    @Query("SELECT * FROM residents ORDER BY name ASC")
    suspend fun getAllResidentsOnce(): List<Resident>

    @Query("SELECT * FROM residents WHERE id = :id")
    suspend fun getResidentById(id: Long): Resident?

    @Query("SELECT * FROM residents WHERE isInZone = 0")
    fun getResidentsOutOfZone(): Flow<List<Resident>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResident(resident: Resident): Long

    @Update
    suspend fun updateResident(resident: Resident)

    @Delete
    suspend fun deleteResident(resident: Resident)

    @Query("DELETE FROM residents WHERE id = :id")
    suspend fun deleteResidentById(id: Long)

    @Query("""
        UPDATE residents 
        SET lastLatitude = :lat, 
            lastLongitude = :lon, 
            lastBattery = :battery, 
            lastSpeed = :speed, 
            lastUpdatedTime = :time,
            isInZone = :inZone,
            distanceFromCenterMeters = :distance
        WHERE id = :id
    """)
    suspend fun updatePosition(
        id: Long,
        lat: Double,
        lon: Double,
        battery: Int?,
        speed: Double?,
        time: Long,
        inZone: Boolean,
        distance: Double
    )
}
