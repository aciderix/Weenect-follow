package fr.alerteresidents.util

/** Une alarme en cours pour un résident. */
data class AlarmInfo(
    val residentId: Long,
    val residentName: String,
    val isDrill: Boolean,
    val isReminder: Boolean,
    val startedAt: Long
)
