package fr.alerteresidents.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dates ISO-8601 de l'API Weenect (compatible minSdk 24, sans java.time). */
object DateParsing {
    private val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    private val fraction = Regex("""(\d{2}:\d{2}:\d{2})\.\d+""")

    fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        // Les fractions de seconde (parfois en microsecondes) sont ignorées : SimpleDateFormat les lit mal.
        val normalized = fraction.replace(value.trim(), "$1")
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                return f.parse(normalized)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun formatIso(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timeMs))

    fun hourOf(timeMs: Long): Int = Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)
}
