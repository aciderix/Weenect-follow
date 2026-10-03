package fr.alerteresidents.desktop

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.desktop.data.DesktopStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `les donnees survivent au redemarrage du poste`() = runTest {
        val dir = tmp.newFolder()
        val store = DesktopStore(dir)
        val id = store.insertResident(Resident(name = "Jeanne Martin", roomNumber = "12"))
        store.saveZone(FacilityZone(name = "Les Tilleuls", radiusMeters = 220.0))
        store.insertAlert(AlertEvent(residentId = id, residentName = "Jeanne Martin", alertType = AlertType.EXIT_ZONE))
        val accountId = store.insert(WeenectAccount(label = "Principal", username = "a@b.fr", encryptedPassword = "x"))
        store.attachAccount(id, accountId)

        val reopened = DesktopStore(dir)
        assertEquals(listOf("Jeanne Martin"), reopened.residents.value.map { it.name })
        assertEquals(accountId, reopened.residents.value.single().accountId)
        assertEquals("Les Tilleuls", reopened.zone.value.name)
        assertEquals(220.0, reopened.zone.value.radiusMeters, 0.0)
        assertEquals(AlertType.EXIT_ZONE, reopened.alerts.value.single().alertType)
        assertEquals("a@b.fr", reopened.findByUsername("a@b.fr")?.username)
    }

    @Test
    fun `les identifiants sont uniques et croissants`() = runTest {
        val store = DesktopStore(tmp.newFolder())
        val a = store.insertResident(Resident(name = "A"))
        val b = store.insertResident(Resident(name = "B"))
        val e1 = store.insertAlert(AlertEvent(residentId = a, residentName = "A", alertType = AlertType.LOW_BATTERY))
        val e2 = store.insertAlert(AlertEvent(residentId = b, residentName = "B", alertType = AlertType.LOW_BATTERY))
        assertNotEquals(a, b)
        assertTrue(e2 > e1)
    }

    @Test
    fun `acquittement par resident et purge du journal`() = runTest {
        val store = DesktopStore(tmp.newFolder())
        val now = System.currentTimeMillis()
        store.insertAlert(AlertEvent(residentId = 1, residentName = "A", alertType = AlertType.EXIT_ZONE, timestamp = now))
        store.insertAlert(AlertEvent(residentId = 2, residentName = "B", alertType = AlertType.EXIT_ZONE, timestamp = now))
        store.insertAlert(AlertEvent(residentId = 1, residentName = "A", alertType = AlertType.LOW_BATTERY, timestamp = now - 40L * 86_400_000, isAcknowledged = true))

        store.acknowledgeForResident(1, AlertType.EXIT_ZONE, "Sophie", now)
        val byResident = store.alerts.value.filter { it.alertType == AlertType.EXIT_ZONE }.associateBy { it.residentId }
        assertTrue(byResident.getValue(1).isAcknowledged)
        assertEquals("Sophie", byResident.getValue(1).acknowledgedBy)
        assertFalse(byResident.getValue(2).isAcknowledged)

        assertEquals(1, store.purgeOlderThan(now - 30L * 86_400_000))
        assertEquals(2, store.alerts.value.size)
    }

    @Test
    fun `un fichier corrompu ne bloque pas le demarrage`() {
        val dir = tmp.newFolder()
        File(dir, "residents.json").writeText("{pas du json")
        val store = DesktopStore(dir)
        assertTrue(store.residents.value.isEmpty())
    }
}
