package fr.alerteresidents.util

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export CSV (Excel) du journal des alertes, partagé Android / Windows. */
object JournalCsv {
    private fun fmt(t: Long?) = t?.let { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(it)) } ?: ""

    fun toCsv(events: List<AlertEvent>): String {
        fun esc(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        val sb = StringBuilder("\uFEFF") // BOM : accents corrects dans Excel
        sb.append("Date;Résident;Événement;Exercice;Distance (m);Latitude;Longitude;Détails;Acquitté;Acquitté par;Acquitté le\n")
        for (e in events) {
            sb.append(
                listOf(
                    esc(fmt(e.timestamp)), esc(e.residentName), esc(AlertType.label(e.alertType)),
                    esc(if (e.isDrill) "oui" else "non"), e.distanceMeters.toInt().toString(),
                    if (e.latitude != 0.0) e.latitude.toString() else "", if (e.longitude != 0.0) e.longitude.toString() else "",
                    esc(e.details), esc(if (e.isAcknowledged) "oui" else "non"), esc(e.acknowledgedBy), esc(fmt(e.acknowledgedAt))
                ).joinToString(";")
            ).append('\n')
        }
        return sb.toString()
    }

}
