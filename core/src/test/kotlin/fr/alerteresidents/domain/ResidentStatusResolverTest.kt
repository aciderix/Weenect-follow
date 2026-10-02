package fr.alerteresidents.domain

import fr.alerteresidents.data.model.Resident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentStatusResolverTest {
    private val now = 10_000_000_000L
    private val ok = Resident(
        id = 1, name = "Safe", accountId = 1, trackerId = 1, lastLatitude = 47.0, lastLongitude = -1.0,
        lastUpdatedTime = now - 60_000, lastSyncTime = now - 10_000, lastBattery = 80
    )
    private fun s(r: Resident) = ResidentStatusResolver.resolve(r, now, 15)

    @Test
    fun statuses() {
        assertEquals(ResidentStatus.SAFE, s(ok))
        assertEquals(ResidentStatus.OUT, s(ok.copy(isInZone = false)))
        assertEquals(ResidentStatus.INACTIVE, s(ok.copy(isTrackingActive = false, isInZone = false)))
        assertEquals(ResidentStatus.PAUSED, s(ok.copy(pausedUntil = now + 1)))
        assertEquals(ResidentStatus.SAFE, s(ok.copy(pausedUntil = now - 1)))
        assertEquals(ResidentStatus.UNKNOWN, s(ok.copy(trackerId = null)))
        assertEquals(ResidentStatus.UNKNOWN, s(ok.copy(lastLatitude = null)))
        assertEquals(ResidentStatus.STALE, s(ok.copy(lastUpdatedTime = now - 16 * 60_000)))
        assertEquals(ResidentStatus.STALE, s(ok.copy(lastUpdatedTime = null)))
    }

    @Test
    fun `a single recent error is tolerated, a persistent one is not`() {
        assertEquals(ResidentStatus.SAFE, s(ok.copy(lastSyncError = "réseau", lastSyncTime = now - 30_000)))
        assertEquals(ResidentStatus.UNKNOWN, s(ok.copy(lastSyncError = "réseau", lastSyncTime = now - 3 * 60_000)))
    }

    @Test
    fun `urgency order`() {
        val list = listOf(
            ok.copy(id = 1, name = "Zoé"),
            ok.copy(id = 2, name = "Out récent", isInZone = false, exitedAt = now - 1_000),
            ok.copy(id = 3, name = "Out ancien", isInZone = false, exitedAt = now - 100_000),
            ok.copy(id = 4, name = "Inconnu", trackerId = null),
            ok.copy(id = 5, name = "Batterie", lastBattery = 10),
            ok.copy(id = 6, name = "Pause", pausedUntil = now + 1000),
            ok.copy(id = 7, name = "Inactif", isTrackingActive = false),
            ok.copy(id = 8, name = "Ancien", lastUpdatedTime = now - 3_600_000),
            ok.copy(id = 9, name = "Arthur à risque", riskLevel = 2)
        )
        val ids = list.sortedWith(ResidentStatusResolver.urgencyComparator(now, 15)).map { it.id }
        assertEquals(listOf(3L, 2L, 4L, 8L, 5L, 9L, 1L, 6L, 7L), ids)
    }

    @Test
    fun `low battery uses a single threshold`() {
        assertTrue(ResidentStatusResolver.isLowBattery(ok.copy(lastBattery = 20)))
        assertFalse(ResidentStatusResolver.isLowBattery(ok.copy(lastBattery = 21)))
        assertFalse(ResidentStatusResolver.isLowBattery(ok.copy(lastBattery = 5, isTrackingActive = false)))
    }

    @Test
    fun initials() {
        assertEquals("JD", Resident(name = "Jean Dupont").initials)
        assertEquals("JP", Resident(name = "Jean-Pierre").initials)
        assertEquals("MD", Resident(name = "  marie de la Tour  Dupuis ").initials)
        assertEquals("JE", Resident(name = "Jeanne").initials)
    }
}
