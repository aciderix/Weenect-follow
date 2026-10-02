package fr.alerteresidents.util

/** Une alarme en cours pour un résident. */
data class AlarmInfo(
    val residentId: Long,
    val residentName: String,
    val isDrill: Boolean,
    val isReminder: Boolean,
    val startedAt: Long,
    /** Alarme déclenchée par un autre appareil (nom du poste), null si détectée ici. */
    val reportedBy: String? = null
)
