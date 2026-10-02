package fr.alerteresidents.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.alerteresidents.data.model.AlertEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertEventDao {
    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<AlertEvent>>

    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC")
    suspend fun getAllAlertsOnce(): List<AlertEvent>

    /** Alertes demandant une action (le journal contient aussi des événements informatifs). */
    @Query(
        "SELECT * FROM alert_events WHERE isAcknowledged = 0 AND alertType IN " +
            "('EXIT_ZONE','LOW_BATTERY','TRACKER_OFFLINE','SYNC_ERROR','MONITORING_DEGRADED') AND isDrill = 0 ORDER BY timestamp DESC"
    )
    fun getUnacknowledgedAlerts(): Flow<List<AlertEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertEvent): Long

    @Query("UPDATE alert_events SET isAcknowledged = 1, acknowledgedBy = :staffName, acknowledgedAt = :at WHERE id = :id")
    suspend fun acknowledgeAlert(id: Long, staffName: String, at: Long = System.currentTimeMillis())

    @Query(
        "UPDATE alert_events SET isAcknowledged = 1, acknowledgedBy = :staffName, acknowledgedAt = :at " +
            "WHERE isAcknowledged = 0 AND residentId = :residentId AND alertType = :type"
    )
    suspend fun acknowledgeForResident(residentId: Long, type: String, staffName: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE alert_events SET isAcknowledged = 1, acknowledgedBy = :staffName, acknowledgedAt = :at WHERE isAcknowledged = 0")
    suspend fun acknowledgeAllAlerts(staffName: String, at: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM alert_events WHERE isAcknowledged = 0 AND residentId = :residentId AND alertType = 'EXIT_ZONE'")
    suspend fun countUnacknowledgedExits(residentId: Long): Int

    @Query("DELETE FROM alert_events WHERE timestamp < :before AND (isAcknowledged = 1 OR alertType NOT IN ('EXIT_ZONE','LOW_BATTERY','TRACKER_OFFLINE','SYNC_ERROR','MONITORING_DEGRADED'))")
    suspend fun purgeOlderThan(before: Long): Int

    @Query("DELETE FROM alert_events")
    suspend fun clearAllAlerts()
}
