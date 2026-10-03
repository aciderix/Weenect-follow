package fr.alerteresidents.domain

import fr.alerteresidents.data.model.Resident

/** État affiché d'un résident, du plus urgent au moins urgent. */
enum class ResidentStatus(val priority: Int, val label: String) {
    OUT(0, "Hors zone"),
    UNKNOWN(1, "Position inconnue"),
    STALE(2, "Signal ancien"),
    SAFE(3, "En sécurité"),
    PAUSED(4, "Sortie accompagnée"),
    INACTIVE(5, "Suivi désactivé")
}

object ResidentStatusResolver {
    /** Erreurs tolérées avant de considérer la position comme inconnue. */
    const val ERROR_GRACE_MS = 2 * 60_000L

    fun resolve(r: Resident, now: Long, staleMinutes: Int): ResidentStatus = when {
        !r.isTrackingActive -> ResidentStatus.INACTIVE
        r.isPaused(now) -> ResidentStatus.PAUSED
        !r.isInZone -> ResidentStatus.OUT
        !r.hasTracker || !r.hasPosition -> ResidentStatus.UNKNOWN
        r.lastSyncError != null && (r.lastSyncTime == null || now - r.lastSyncTime > ERROR_GRACE_MS) -> ResidentStatus.UNKNOWN
        r.lastUpdatedTime == null || now - r.lastUpdatedTime > staleMinutes * 60_000L -> ResidentStatus.STALE
        else -> ResidentStatus.SAFE
    }

    fun isLowBattery(r: Resident): Boolean =
        r.isTrackingActive && (r.lastBattery ?: 100) <= Thresholds.LOW_BATTERY

    /** Raison lisible d'un statut UNKNOWN. */
    fun unknownReason(r: Resident): String = when {
        r.trackerId == null -> "Aucune balise associée"
        r.accountId == null -> "Aucun compte Weenect associé"
        r.lastSyncError != null -> r.lastSyncError
        !r.hasPosition -> "En attente d'une première position"
        else -> "Position inconnue"
    }

    /**
     * Tri par urgence : hors zone (le plus ancien d'abord), inconnu, signal ancien, batterie faible,
     * en sécurité, en pause, désactivé. À urgence égale : risque de fugue décroissant puis nom.
     */
    fun urgencyComparator(now: Long, staleMinutes: Int): Comparator<Resident> =
        compareBy<Resident>(
            { rank(it, now, staleMinutes) },
            { if (!it.isInZone) it.exitedAt ?: Long.MAX_VALUE else 0L },
            { -it.riskLevel },
            { it.name.lowercase() }
        )

    private fun rank(r: Resident, now: Long, staleMinutes: Int): Int {
        val status = resolve(r, now, staleMinutes)
        return when (status) {
            ResidentStatus.OUT -> 0
            ResidentStatus.UNKNOWN -> 1
            ResidentStatus.STALE -> 2
            ResidentStatus.SAFE -> if (isLowBattery(r)) 3 else 4
            ResidentStatus.PAUSED -> 5
            ResidentStatus.INACTIVE -> 6
        }
    }
}

/** Texte de la notification permanente de surveillance (état réel, sans résident oublié). */
object MonitoringSummary {
    fun ongoingText(statuses: List<ResidentStatus>, allFailed: Boolean, night: Boolean): String {
        val out = statuses.count { it == ResidentStatus.OUT }
        val unknown = statuses.count { it == ResidentStatus.UNKNOWN || it == ResidentStatus.STALE }
        val safe = statuses.count { it == ResidentStatus.SAFE }
        val paused = statuses.count { it == ResidentStatus.PAUSED }
        val pausedPart = if (paused > 0) " • $paused en sortie accompagnée" else ""
        val text = when {
            out > 0 -> "🚨 $out résident(s) hors zone !" + (if (unknown > 0) " • $unknown sans position fiable" else "") + pausedPart
            allFailed -> "⚠️ Surveillance dégradée : aucune balise joignable"
            unknown > 0 -> "⚠️ $unknown résident(s) sans position fiable • $safe en sécurité$pausedPart"
            safe == 0 && paused > 0 -> "⏸️ $paused résident(s) en sortie accompagnée : surveillance de zone suspendue"
            safe == 0 -> "Aucun résident suivi"
            else -> "🟢 $safe résident(s) en sécurité$pausedPart"
        }
        return text + if (night) " • mode nuit" else ""
    }
}
