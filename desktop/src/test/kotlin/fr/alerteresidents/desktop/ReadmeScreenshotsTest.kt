package fr.alerteresidents.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.ui.theme.MyApplicationTheme
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * Captures de l'app Windows pour le README (établissement fictif).
 * Lancé seulement avec -PscreenshotDir=… : ./gradlew :desktop:test --tests '*ReadmeScreenshots*' -PscreenshotDir=docs/captures
 */
@OptIn(ExperimentalTestApi::class)
class ReadmeScreenshotsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir: String? = System.getProperty("screenshotDir")

    private fun demoApp(): DesktopApp = DesktopApp(dataDir = tmp.newFolder(), baseUrl = "http://127.0.0.1:9/").apply {
        val now = System.currentTimeMillis()
        // Établissement fictif (lieu public quelconque) : aucune adresse réelle dans le README.
        val lat = 45.7772
        val lon = 4.8556
        store.saveZone(FacilityZone(name = "MAS Les Tilleuls", centerLatitude = lat, centerLongitude = lon, radiusMeters = 150.0))
        val account = runBlocking { store.insert(WeenectAccount(label = "Établissement", username = "contact@mas-tilleuls.fr", encryptedPassword = cipher.encrypt("demo"))) }
        fun r(name: String, room: String, tracker: Long, dLat: Double, dLon: Double, minutesAgo: Int = 0, battery: Int = 78) = Resident(
            name = name, roomNumber = room, trackerId = tracker, accountId = account, unit = if (room.toInt() < 6) "Étage 1" else "Étage 2",
            lastLatitude = lat + dLat, lastLongitude = lon + dLon, lastUpdatedTime = now - minutesAgo * 60_000L, lastSyncTime = now,
            lastBattery = battery
        )
        store.insertResident(r("Jeanne Martin", "12", 1, 0.0040, 0.0002).copy(
            isInZone = false, distanceFromCenterMeters = 445.0, exitedAt = now - 6 * 60_000L, alertState = AlertState.ACTIVE))
        store.insertResident(r("Paul Lefèvre", "3", 2, 0.0004, 0.0051).copy(
            isInZone = false, distanceFromCenterMeters = 390.0, exitedAt = now - 2 * 60_000L, alertState = AlertState.HANDLING,
            alertHandledBy = "Paula", alertHandledAt = now - 60_000L))
        store.insertResident(r("Henri Moreau", "7", 3, -0.0004, -0.0005, battery = 64))
        store.insertResident(r("Marie Dubois", "5", 4, 0.0003, -0.0007, battery = 91))
        store.insertResident(r("Lucie Bernard", "9", 5, 0.0006, 0.0004).copy(lastLatitude = null, lastLongitude = null, lastUpdatedTime = null,
            lastSyncError = "Aucune position reçue de la balise", lastSyncErrorAt = now))
        store.insertResident(r("André Petit", "8", 6, -0.0006, 0.0006, minutesAgo = 49, battery = 12))
        store.insertResident(r("Odette Garnier", "2", 7, 0.0001, 0.0003).copy(pausedUntil = now + 90 * 60_000L, pauseReason = "Rendez-vous médical"))
        runBlocking {
            store.insertAlert(AlertEvent(residentId = 1, residentName = "Jeanne Martin", alertType = AlertType.EXIT_ZONE, timestamp = now - 6 * 60_000L, distanceMeters = 445.0))
            store.insertAlert(AlertEvent(residentId = 6, residentName = "André Petit", alertType = AlertType.LOW_BATTERY, timestamp = now - 20 * 60_000L, details = "Batterie à 12 %"))
            store.insertAlert(AlertEvent(residentId = 2, residentName = "Paul Lefèvre", alertType = AlertType.HANDLING, timestamp = now - 60_000L,
                isAcknowledged = true, acknowledgedBy = "Paula", details = "Pris en charge par Paula"))
        }
        preferences.setStaffName("Paula")
        MonitoringHealth.update {
            it.copy(serviceRunning = true, lastCycleAt = now, lastSuccessAt = now, activeCount = 7, okCount = 6, errorCount = 1, consecutiveFailedCycles = 0)
        }
    }

    private fun save(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(dir!!, "$name.png").apply { parentFile.mkdirs() })
    }

    @Test
    fun captures() {
        assumeTrue("Captures générées seulement avec -PscreenshotDir", dir != null)
        runDesktopComposeUiTest(1440, 860) {
            val vm = ResidentViewModel(demoApp())
            setContent { MyApplicationTheme { AppContent(vm) } }
            waitForIdle()
            vm.selectResident(1)
            // Laisse le temps aux fonds de carte (téléchargés en arrière-plan) d'arriver.
            repeat(15) {
                Thread.sleep(1_000)
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
            }
            save("windows_1_suivi_et_carte", onRoot().captureToImage().toAwtImage())

            onNodeWithTag("nav_Journal").performClick()
            waitForIdle()
            save("windows_2_journal", onRoot().captureToImage().toAwtImage())
        }
    }
}
