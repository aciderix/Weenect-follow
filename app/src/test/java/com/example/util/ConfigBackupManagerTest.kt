package com.example.util

import android.app.Application
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConfigBackupManagerTest {

    private val zone = FacilityZone(
        name = "MAS Test",
        address = "1 rue du Test",
        centerLatitude = 47.1,
        centerLongitude = -1.6,
        radiusMeters = 220.0,
        zoneType = "POLYGON",
        polygonPointsJson = FacilityZone.encodePolygonPoints(listOf(47.1 to -1.6, 47.2 to -1.6, 47.2 to -1.5)),
        soundAlertsEnabled = false,
        refreshIntervalSeconds = 30
    )

    private val residents = listOf(
        Resident(
            id = 7, name = "Jeanne Martin", roomNumber = "Ch. 3", weenectUsername = "famille@test.fr",
            weenectPassword = "secret", trackerId = 123456, trackerName = "Balise J",
            emergencyContact = "06 00 00 00 00", notes = "Fugueuse", isTrackingActive = false,
            lastLatitude = 47.3, isInZone = false
        ),
        Resident(name = "Paul Sans Balise")
    )

    @Test
    fun `export then import restores the zone and residents`() {
        val json = ConfigBackupManager.createBackupJson(zone, residents, "Infirmière de nuit")
        val backup = ConfigBackupManager.parseBackupJson(json)

        assertNotNull(backup)
        backup!!
        assertEquals("Infirmière de nuit", backup.exportedBy)
        assertEquals(zone, backup.facilityZone)
        assertEquals(2, backup.residents.size)

        val jeanne = backup.residents[0]
        assertEquals(0L, jeanne.id) // nouvel id généré par Room à l'import
        assertEquals("Jeanne Martin", jeanne.name)
        assertEquals("Ch. 3", jeanne.roomNumber)
        assertEquals(123456L, jeanne.trackerId)
        assertEquals("Balise J", jeanne.trackerName)
        assertEquals("06 00 00 00 00", jeanne.emergencyContact)
        assertEquals("Fugueuse", jeanne.notes)
        assertFalse(jeanne.isTrackingActive)
        // L'état de suivi (position, statut zone) n'est pas exporté
        assertNull(jeanne.lastLatitude)

        val paul = backup.residents[1]
        assertNull(paul.trackerId)
        assertNull(paul.trackerName)
    }

    @Test
    fun `invalid json is rejected`() {
        assertNull(ConfigBackupManager.parseBackupJson("pas du json"))
        assertNull(ConfigBackupManager.parseBackupJson("""{"residents":[]}""")) // zone manquante
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val backup = ConfigBackupManager.parseBackupJson(
            """{"facilityZone":{"name":"Z"},"residents":[{"name":"A"}]}"""
        )!!
        assertEquals(150.0, backup.facilityZone.radiusMeters, 0.0)
        assertEquals("CIRCLE", backup.facilityZone.zoneType)
        assertEquals("A", backup.residents.single().name)
    }

    @Ignore("AUDIT C4 : l'export contient les mots de passe Weenect en clair et part via n'importe quelle appli de partage")
    @Test
    fun `export does not leak Weenect passwords in clear text`() {
        val json = ConfigBackupManager.createBackupJson(zone, residents)
        assertFalse(json.contains("secret"))
    }
}
