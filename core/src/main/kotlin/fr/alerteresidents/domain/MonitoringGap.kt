package fr.alerteresidents.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trou dans la surveillance : entre le dernier cycle connu et la relance du service, aucune
 * balise n'a été interrogée (appli tuée par Android ou le fabricant, plantage, téléphone éteint).
 * Détecté à la relance pour être tracé dans le journal au lieu de passer inaperçu.
 */
data class MonitoringGap(val from: Long, val to: Long, val crash: String? = null) {
    val durationMs: Long get() = to - from

    /** [probableCause] : explication si aucun plantage n'a été enregistré (dépend de la plateforme). */
    fun message(probableCause: String = CAUSE_ANDROID): String = buildString {
        append("Surveillance interrompue de ${clock(from)} à ${clock(to)} (${duration(durationMs)}) : ")
        append("aucune balise n'a été interrogée pendant ce temps. ")
        if (crash != null) append("Cause : plantage de l'application ($crash). ")
        else append("Cause probable : $probableCause. ")
        append("Vérifiez l'écran État de la surveillance.")
    }

    private fun clock(t: Long): String {
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).let { it.format(Date(t)) == it.format(Date(to)) }
        return SimpleDateFormat(if (sameDay) "HH:mm" else "dd/MM HH:mm", Locale.FRANCE).format(Date(t))
    }

    companion object {
        /** En dessous, c'est une relance normale (mise à jour de l'app, redémarrage rapide). */
        const val MIN_GAP_MS = 5 * 60_000L
        const val CAUSE_ANDROID = "application arrêtée par Android ou par l'économiseur de batterie du téléphone, ou téléphone éteint"
        const val CAUSE_DESKTOP = "poste éteint ou mis en veille, ou application fermée"

        /**
         * Trou à signaler, ou null. [lastBeatAt] : dernier cycle enregistré (null au premier
         * lancement) ; [intervalSeconds] : intervalle de synchro alors en vigueur.
         */
        fun detect(lastBeatAt: Long?, now: Long, intervalSeconds: Int = 15, crash: String? = null): MonitoringGap? {
            if (lastBeatAt == null || lastBeatAt <= 0 || lastBeatAt > now) return null
            val threshold = maxOf(MIN_GAP_MS, intervalSeconds * 1000L * 4)
            return if (now - lastBeatAt > threshold) MonitoringGap(lastBeatAt, now, crash) else null
        }

        fun duration(ms: Long): String {
            val minutes = (ms / 60_000L).coerceAtLeast(1)
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0L -> "$m min"
                m == 0L -> "$h h"
                else -> "$h h $m min"
            }
        }
    }
}
