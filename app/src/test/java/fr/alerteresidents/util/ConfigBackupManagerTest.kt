package fr.alerteresidents.util

import android.app.Application
import fr.alerteresidents.data.model.ExtraZone
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConfigBackupManagerTest {

    private val zone = FacilityZone(
        name = "MAS Test", address = "1 rue du Test", centerLatitude = 47.1, centerLongitude = -1.6, radiusMeters = 220.0,
        zoneType = "POLYGON", polygonPointsJson = FacilityZone.encodePolygonPoints(listOf(47.1 to -1.6, 47.2 to -1.6, 47.2 to -1.5)),
        soundAlertsEnabled = false, refreshIntervalSeconds = 30,
        extraZonesJson = FacilityZone.encodeExtraZones(listOf(ExtraZone("Jardin", 47.11, -1.61, 40.0))),
        nightModeEnabled = true, nightStartHour = 22, nightEndHour = 6, nightRefreshIntervalSeconds = 12
    )
    private val account = WeenectAccount(id = 5, label = "Balises MAS", username = "mas@test.fr", encryptedPassword = "x")
    private val residents = listOf(
        Resident(
            id = 7, name = "Jeanne Martin", roomNumber = "Ch. 3", accountId = 5, trackerId = 123456, trackerName = "Balise J",
            emergencyContact = "06 00 00 00 00", notes = "Fugueuse", isTrackingActive = false, unit = "Étage 1", riskLevel = 2,
            lastLatitude = 47.3, isInZone = false
        ),
        Resident(name = "Paul Sans Balise")
    )

    @Test
    fun `export then import restores zone, residents and accounts without passwords`() {
        val json = ConfigBackupManager.createBackupJson(zone, residents, "Infirmière de nuit", listOf(account to null))
        assertFalse("aucun mot de passe en clair", json.contains("secret"))
        val backup = ConfigBackupManager.parseBackupJson(json)!!

        assertEquals("Infirmière de nuit", backup.exportedBy)
        assertEquals(zone, backup.facilityZone)
        assertEquals(listOf(BackupAccount(5, "Balises MAS", "mas@test.fr")), backup.accounts)
        assertFalse(backup.hasEncryptedPasswords)
        val jeanne = backup.residents[0]
        assertEquals(0L, jeanne.id)
        assertEquals(5L, jeanne.accountId) // référence au compte du fichier
        assertEquals(123456L, jeanne.trackerId)
        assertEquals("Étage 1", jeanne.unit)
        assertEquals(2, jeanne.riskLevel)
        assertFalse(jeanne.isTrackingActive)
        assertNull("l'état de suivi n'est pas exporté", jeanne.lastLatitude)
        assertNull(backup.residents[1].trackerId)
    }

    @Test
    fun `passwords are exported encrypted with the passphrase`() {
        val json = ConfigBackupManager.createBackupJson(zone, residents, "X", listOf(account to "secret"), passphrase = "code-456")
        assertFalse(json.contains("secret"))
        val backup = ConfigBackupManager.parseBackupJson(json)!!
        assertTrue(backup.hasEncryptedPasswords)
        assertEquals(mapOf(5L to "secret"), ConfigBackupManager.decryptSecrets(backup, "code-456"))
        assertNull(ConfigBackupManager.decryptSecrets(backup, "mauvais"))
    }

    @Test
    fun `version 1 files with clear text credentials are still readable`() {
        val v1 = """{"schemaVersion":1,"facilityZone":{"name":"Ancien","radiusMeters":150},
            "residents":[{"name":"A","weenectUsername":"u@test.fr","weenectPassword":"p","trackerId":9}]}"""
        val backup = ConfigBackupManager.parseBackupJson(v1)!!
        assertTrue(backup.hasLegacyPlaintextCredentials)
        assertEquals("u@test.fr", backup.residents.single().weenectUsername)
        assertEquals(9L, backup.residents.single().trackerId)
    }

    @Test
    fun `invalid json or invalid zone is rejected`() {
        assertNull(ConfigBackupManager.parseBackupJson("pas du json"))
        assertNull(ConfigBackupManager.parseBackupJson("""{"residents":[]}"""))
        assertNull(ConfigBackupManager.parseBackupJson("""{"facilityZone":{"centerLatitude":120}}"""))
        assertNull(ConfigBackupManager.parseBackupJson("""{"facilityZone":{"radiusMeters":-5}}"""))
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val backup = ConfigBackupManager.parseBackupJson("""{"facilityZone":{"name":"Z"},"residents":[{"name":"A","riskLevel":9}]}""")
        assertNotNull(backup)
        assertEquals(150.0, backup!!.facilityZone.radiusMeters, 0.0)
        assertEquals(2, backup.residents.single().riskLevel)
    }
}
