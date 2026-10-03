package fr.alerteresidents.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cycle de vie d'une alerte de sortie, indépendant de la position remontée par la balise. */
object AlertState {
    const val NONE = "NONE"
    /** Sortie confirmée, personne n'a encore pris l'alerte en charge. */
    const val ACTIVE = "ACTIVE"
    /** Un soignant a indiqué « Je m'en occupe ». */
    const val HANDLING = "HANDLING"
    /** Le résident a été retrouvé ; en attente d'une position de retour de la balise. */
    const val RESOLVED = "RESOLVED"
}

@Entity(tableName = "residents")
data class Resident(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val roomNumber: String = "",
    val photoUri: String? = null,
    val avatarColorHex: String = "#1E88E5",
    /** Ancien stockage des identifiants (v3). Vidé au démarrage : voir [WeenectAccount]. */
    val weenectUsername: String = "",
    val weenectPassword: String = "",
    val trackerId: Long? = null,
    val trackerName: String? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastBattery: Int? = null,
    val lastSpeed: Double? = null,
    /** Heure du dernier fix de la balise (date_tracker), et non l'heure de la requête. */
    val lastUpdatedTime: Long? = null,
    /** false uniquement quand une sortie de zone est confirmée. */
    val isInZone: Boolean = true,
    val distanceFromCenterMeters: Double = 0.0,
    val emergencyContact: String = "",
    val notes: String = "",
    val isTrackingActive: Boolean = true,
    // --- v4 ---
    val accountId: Long? = null,
    /** Unité de vie / étage, pour regrouper la liste. */
    val unit: String = "",
    /** 0 = standard, 1 = vigilance, 2 = risque élevé de fugue. */
    val riskLevel: Int = 0,
    /** Dernière réponse réussie de l'API Weenect. */
    val lastSyncTime: Long? = null,
    /** Dernière erreur de synchronisation (null si la dernière tentative a réussi). */
    val lastSyncError: String? = null,
    val lastSyncErrorAt: Long? = null,
    /** Précision estimée du dernier fix, en mètres. */
    val accuracyMeters: Int? = null,
    val lastFixId: String? = null,
    /** Fix hors zone en attente de confirmation (hystérésis). */
    val pendingExitFixId: String? = null,
    val pendingExitAt: Long? = null,
    val exitedAt: Long? = null,
    val alertState: String = AlertState.NONE,
    val alertHandledBy: String? = null,
    val alertHandledAt: Long? = null,
    val lastAlarmAt: Long? = null,
    /** Sortie accompagnée : surveillance suspendue jusqu'à cette heure. */
    val pausedUntil: Long? = null,
    val pauseReason: String? = null,
    val lowBatteryNotified: Boolean = false,
    val offlineNotified: Boolean = false,
    val isInDeepSleep: Boolean = false,
    // --- v5 ---
    /** Identifiant partagé entre appareils (synchronisation Supabase), null tant que non synchronisé. */
    val syncId: String? = null
) {
    val hasPosition: Boolean get() = lastLatitude != null && lastLongitude != null
    val hasTracker: Boolean get() = trackerId != null && accountId != null

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = pausedUntil != null && pausedUntil > now

    /** Initiales : « Jean Dupont » → « JD », « Jeanne » → « JE ». */
    val initials: String
        get() {
            val parts = name.trim().split(Regex("[\\s\\-]+")).filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
                parts.size == 1 -> parts[0].take(2)
                else -> "?"
            }.uppercase()
        }
}

/** Colonnes modifiables depuis la fiche résident (ne touche jamais l'état de suivi). */
data class ResidentProfile(
    val id: Long,
    val name: String,
    val roomNumber: String,
    val photoUri: String?,
    val avatarColorHex: String,
    val accountId: Long?,
    val trackerId: Long?,
    val trackerName: String?,
    val emergencyContact: String,
    val notes: String,
    val isTrackingActive: Boolean,
    val unit: String,
    val riskLevel: Int
)

/** Colonnes écrites par le moteur de surveillance (ne touche jamais la fiche). */
data class ResidentTracking(
    val id: Long,
    val lastLatitude: Double?,
    val lastLongitude: Double?,
    val lastBattery: Int?,
    val lastSpeed: Double?,
    val lastUpdatedTime: Long?,
    val isInZone: Boolean,
    val distanceFromCenterMeters: Double,
    val lastSyncTime: Long?,
    val lastSyncError: String?,
    val lastSyncErrorAt: Long?,
    val accuracyMeters: Int?,
    val lastFixId: String?,
    val pendingExitFixId: String?,
    val pendingExitAt: Long?,
    val exitedAt: Long?,
    val alertState: String,
    val alertHandledBy: String?,
    val alertHandledAt: Long?,
    val lastAlarmAt: Long?,
    val pausedUntil: Long?,
    val pauseReason: String?,
    val lowBatteryNotified: Boolean,
    val offlineNotified: Boolean,
    val isInDeepSleep: Boolean
) {
    companion object {
        fun of(r: Resident) = ResidentTracking(
            id = r.id, lastLatitude = r.lastLatitude, lastLongitude = r.lastLongitude,
            lastBattery = r.lastBattery, lastSpeed = r.lastSpeed, lastUpdatedTime = r.lastUpdatedTime,
            isInZone = r.isInZone, distanceFromCenterMeters = r.distanceFromCenterMeters,
            lastSyncTime = r.lastSyncTime, lastSyncError = r.lastSyncError, lastSyncErrorAt = r.lastSyncErrorAt,
            accuracyMeters = r.accuracyMeters, lastFixId = r.lastFixId, pendingExitFixId = r.pendingExitFixId,
            pendingExitAt = r.pendingExitAt,
            exitedAt = r.exitedAt, alertState = r.alertState, alertHandledBy = r.alertHandledBy,
            alertHandledAt = r.alertHandledAt, lastAlarmAt = r.lastAlarmAt, pausedUntil = r.pausedUntil,
            pauseReason = r.pauseReason, lowBatteryNotified = r.lowBatteryNotified,
            offlineNotified = r.offlineNotified, isInDeepSleep = r.isInDeepSleep
        )
    }
}
