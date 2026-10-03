package fr.alerteresidents.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MonitoringGapTest {
    private fun at(hour: Int, minute: Int, day: Int = 3): Long =
        Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun `premier lancement ou relance rapide - rien a signaler`() {
        assertNull(MonitoringGap.detect(null, at(10, 0)))
        assertNull(MonitoringGap.detect(0L, at(10, 0)))
        assertNull(MonitoringGap.detect(at(10, 0), at(10, 3)))
        // Horloge du téléphone reculée : pas de faux trou
        assertNull(MonitoringGap.detect(at(10, 30), at(10, 0)))
    }

    @Test
    fun `appli tuee de 10h50 a 19h52 - interruption datee et expliquee`() {
        val gap = MonitoringGap.detect(at(10, 50), at(19, 52))
        assertNotNull(gap)
        assertEquals(
            "Surveillance interrompue de 10:50 à 19:52 (9 h 2 min) : aucune balise n'a été interrogée pendant ce temps. " +
                "Cause probable : ${MonitoringGap.CAUSE_ANDROID}. Vérifiez l'écran État de la surveillance.",
            gap!!.message()
        )
    }

    @Test
    fun `plantage enregistre - cause donnee, la veille datee`() {
        val msg = MonitoringGap.detect(at(23, 40, day = 2), at(6, 10), crash = "IllegalStateException : boom")!!.message()
        assertTrue(msg, msg.startsWith("Surveillance interrompue de 02/10 23:40 à 06:10 (6 h 30 min)"))
        assertTrue(msg, msg.contains("Cause : plantage de l'application (IllegalStateException : boom)"))
    }

    @Test
    fun `seuil adapte a un intervalle de synchro long`() {
        // Intervalle de nuit de 120 s : 6 min sans cycle reste normal (4 intervalles = 8 min)
        assertNull(MonitoringGap.detect(at(2, 0), at(2, 6), intervalSeconds = 120))
        assertNotNull(MonitoringGap.detect(at(2, 0), at(2, 9), intervalSeconds = 120))
    }

    @Test
    fun durees() {
        assertEquals("1 min", MonitoringGap.duration(10_000L))
        assertEquals("45 min", MonitoringGap.duration(45 * 60_000L))
        assertEquals("2 h", MonitoringGap.duration(120 * 60_000L))
        assertEquals("1 h 5 min", MonitoringGap.duration(65 * 60_000L))
    }
}
