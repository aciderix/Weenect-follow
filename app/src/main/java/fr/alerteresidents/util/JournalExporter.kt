package fr.alerteresidents.util

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export du journal des alertes (rapports d'événements indésirables). */
object JournalExporter {
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

    fun toPdf(events: List<AlertEvent>, facilityName: String, exportedBy: String): ByteArray {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 36f
        val title = Paint().apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint().apply { textSize = 9.5f }
        val bold = Paint().apply { textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD }
        val grey = Paint().apply { textSize = 8.5f; color = android.graphics.Color.DKGRAY }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin + 10f
            val c = page!!.canvas
            c.drawText("Journal des alertes — $facilityName", margin, y, title)
            y += 16f
            c.drawText("Exporté le ${fmt(System.currentTimeMillis())} par $exportedBy • page $pageNumber", margin, y, grey)
            y += 18f
        }
        newPage()
        for (e in events) {
            if (y > pageHeight - margin - 40f) newPage()
            val c = page!!.canvas
            val head = "${fmt(e.timestamp)}  •  ${e.residentName}  •  ${AlertType.label(e.alertType)}" + if (e.isDrill) "  (EXERCICE)" else ""
            c.drawText(head, margin, y, bold)
            y += 12f
            val detail = buildString {
                if (e.alertType == AlertType.EXIT_ZONE || e.alertType == AlertType.ENTER_ZONE) append("Distance : ${e.distanceMeters.toInt()} m. ")
                e.details?.let { append(it).append(". ") }
                if (e.isAcknowledged) append("Acquitté par ${e.acknowledgedBy ?: "—"} ${fmt(e.acknowledgedAt)}")
                else if (e.alertType in AlertType.ACTIONABLE) append("NON ACQUITTÉ")
            }
            if (detail.isNotBlank()) {
                c.drawText(detail.take(130), margin + 10f, y, body)
                y += 12f
            }
            y += 4f
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    fun share(context: Context, uri: Uri, mime: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
