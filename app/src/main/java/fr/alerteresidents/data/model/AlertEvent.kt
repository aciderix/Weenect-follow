package fr.alerteresidents.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

object AlertType {
    const val EXIT_ZONE = "EXIT_ZONE"
    const val ENTER_ZONE = "ENTER_ZONE"
    const val LOW_BATTERY = "LOW_BATTERY"
    const val TRACKER_OFFLINE = "TRACKER_OFFLINE"
    const val SYNC_ERROR = "SYNC_ERROR"
    const val MONITORING_DEGRADED = "MONITORING_DEGRADED"
    const val HANDLING = "HANDLING"
    const val RESOLVED = "RESOLVED"
    const val OUTING_START = "OUTING_START"
    const val OUTING_END = "OUTING_END"
    const val SHIFT_CHECK = "SHIFT_CHECK"

    /** Types qui demandent une action (badge rouge, acquittement). */
    val ACTIONABLE = setOf(EXIT_ZONE, LOW_BATTERY, TRACKER_OFFLINE, SYNC_ERROR, MONITORING_DEGRADED)

    fun label(type: String): String = when (type) {
        EXIT_ZONE -> "Sortie de zone"
        ENTER_ZONE -> "Retour dans la zone"
        LOW_BATTERY -> "Batterie faible"
        TRACKER_OFFLINE -> "Balise muette"
        SYNC_ERROR -> "Erreur de connexion Weenect"
        MONITORING_DEGRADED -> "Surveillance dégradée"
        HANDLING -> "Prise en charge"
        RESOLVED -> "Résident retrouvé"
        OUTING_START -> "Début de sortie accompagnée"
        OUTING_END -> "Fin de sortie accompagnée"
        SHIFT_CHECK -> "Prise de poste"
        else -> type
    }
}

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val residentId: Long,
    val residentName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val alertType: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val isAcknowledged: Boolean = false,
    val acknowledgedBy: String? = null,
    // --- v4 ---
    val acknowledgedAt: Long? = null,
    /** Événement généré par un exercice / une simulation. */
    val isDrill: Boolean = false,
    val details: String? = null
)
