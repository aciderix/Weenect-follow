package fr.alerteresidents.domain

import fr.alerteresidents.domain.ResidentStatus.INACTIVE
import fr.alerteresidents.domain.ResidentStatus.OUT
import fr.alerteresidents.domain.ResidentStatus.PAUSED
import fr.alerteresidents.domain.ResidentStatus.SAFE
import fr.alerteresidents.domain.ResidentStatus.STALE
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringSummaryTest {
    private fun text(vararg s: ResidentStatus, failed: Boolean = false, night: Boolean = false) =
        MonitoringSummary.ongoingText(s.toList(), failed, night)

    @Test
    fun `seul resident en sortie accompagnee - plus de 0 en securite trompeur`() {
        assertEquals("⏸️ 1 résident(s) en sortie accompagnée : surveillance de zone suspendue", text(PAUSED))
    }

    @Test
    fun `residents en securite et en sortie - les deux sont comptes`() {
        assertEquals("🟢 2 résident(s) en sécurité • 1 en sortie accompagnée", text(SAFE, SAFE, PAUSED))
    }

    @Test
    fun `alertes prioritaires`() {
        assertEquals("🚨 1 résident(s) hors zone ! • 1 sans position fiable • 1 en sortie accompagnée", text(OUT, STALE, PAUSED))
        assertEquals("⚠️ Surveillance dégradée : aucune balise joignable", text(SAFE, failed = true))
        assertEquals("⚠️ 1 résident(s) sans position fiable • 1 en sécurité", text(STALE, SAFE))
    }

    @Test
    fun `aucun resident suivi et mode nuit`() {
        assertEquals("Aucun résident suivi", text(INACTIVE))
        assertEquals("Aucun résident suivi", text())
        assertEquals("🟢 1 résident(s) en sécurité • mode nuit", text(SAFE, night = true))
    }
}
