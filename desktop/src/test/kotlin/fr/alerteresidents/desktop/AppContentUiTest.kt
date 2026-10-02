package fr.alerteresidents.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.ui.theme.MyApplicationTheme
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class AppContentUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun app(): DesktopApp {
        val dir = tmp.newFolder()
        // Serveur injoignable : aucun appel réseau réel pendant le test.
        return DesktopApp(dataDir = dir, baseUrl = "http://127.0.0.1:9/").apply {
            val now = System.currentTimeMillis()
            store.insertResident(Resident(name = "Jeanne Martin", roomNumber = "12", lastLatitude = 47.1787, lastLongitude = -1.6192, lastUpdatedTime = now, lastSyncTime = now, lastBattery = 80))
            store.insertResident(Resident(name = "Paul Durand", roomNumber = "7", lastLatitude = 47.1815, lastLongitude = -1.6150, lastUpdatedTime = now, lastSyncTime = now, isInZone = false, distanceFromCenterMeters = 320.0, exitedAt = now - 120_000, alertState = "ACTIVE"))
        }
    }

    private fun ComposeUiTest.assertTextShown(text: String) =
        assertTrue("« $text » absent", onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

    private fun screenshot(name: String, block: () -> java.awt.image.BufferedImage) {
        val dir = System.getProperty("screenshotDir") ?: return
        runCatching { ImageIO.write(block(), "png", File(dir, "$name.png").apply { parentFile.mkdirs() }) }
    }

    @Test
    fun `navigation entre les ecrans principaux`() = runDesktopComposeUiTest(1400, 900) {
        val vm = ResidentViewModel(app())
        setContent { MyApplicationTheme { AppContent(vm) } }
        waitForIdle()
        onNodeWithTag("main_navigation_rail").assertIsDisplayed()
        assertTextShown("Jeanne Martin")
        screenshot("suivi") { onRoot().captureToImage().toAwtImage() }

        onNodeWithTag("nav_Journal").performClick()
        waitForIdle()
        onNodeWithTag("nav_État").performClick()
        waitForIdle()
        screenshot("etat") { onRoot().captureToImage().toAwtImage() }
        onNodeWithTag("nav_Paramètres").performClick()
        waitForIdle()
        assertTextShown("Windows")
        assertTextShown("Partage entre appareils")
        assertTextShown("Non configuré")
        onAllNodesWithText("Connecter à Supabase", useUnmergedTree = true)[0].performScrollTo()
        waitForIdle()
        screenshot("partage") { onRoot().captureToImage().toAwtImage() }
        screenshot("parametres") { onRoot().captureToImage().toAwtImage() }
    }

    @Test
    fun `une alarme affiche la fenetre d alarme`() = runComposeUiTest {
        val app = app()
        val vm = ResidentViewModel(app)
        setContent { MyApplicationTheme { AppContent(vm) } }
        waitForIdle()
        vm.triggerManualLoudAlarmTest()
        waitForIdle()
        assertTextShown("Test d'alarme")
        screenshot("alarme") { onRoot().captureToImage().toAwtImage() }
        app.alarms.stopAlarm()
    }
}
