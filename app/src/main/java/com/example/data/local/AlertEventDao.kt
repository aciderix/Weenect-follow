package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.AlertEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertEventDao {
    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<AlertEvent>>

    @Query("SELECT * FROM alert_events WHERE isAcknowledged = 0 ORDER BY timestamp DESC")
    fun getUnacknowledgedAlerts(): Flow<List<AlertEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertEvent): Long

    @Query("UPDATE alert_events SET isAcknowledged = 1, acknowledgedBy = :staffName WHERE id = :id")
    suspend fun acknowledgeAlert(id: Long, staffName: String)

    @Query("UPDATE alert_events SET isAcknowledged = 1 WHERE isAcknowledged = 0")
    suspend fun acknowledgeAllAlerts()

    @Query("DELETE FROM alert_events")
    suspend fun clearAllAlerts()
}
