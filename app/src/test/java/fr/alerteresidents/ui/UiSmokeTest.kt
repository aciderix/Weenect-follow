package fr.alerteresidents.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import fr.alerteresidents.MainActivity
import fr.alerteresidents.SecuriResidentApp
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.util.DashboardViewMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rendu réel des écrans (Robolectric + Compose) avec plusieurs résidents dans tous les états.
 * Vérifie l'absence de plantage et enregistre des captures dans build/ui-screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
open class UiSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    protected open val suffix = "light"
    private val app get() = ApplicationProvider.getApplicationContext<SecuriResidentApp>()

    @Before
    fun seed() = runBlocking {
        val now = System.currentTimeMillis()
        val db = app.database
        db.facilityZoneDao().insertOrUpdate(FacilityZone(name = "MAS Les Tilleuls", centerLatitude = 47.0, centerLongitude = -1.0, radiusMeters = 150.0))
        val acc = db.weenectAccountDao().insert(WeenectAccount(label = "Balises MAS", username = "mas@test.fr", encryptedPassword = "x"))
        fun r(name: String, room: String, unit: String, block: Resident.() -> Resident) =
            Resident(name = name, roomNumber = room, unit = unit, accountId = acc, trackerId = name.hashCode().toLong(),
                lastLatitude = 47.0003, lastLongitude = -1.0002, lastUpdatedTime = now - 60_000, lastSyncTime = now - 10_000,
                lastBattery = 76, distanceFromCenterMeters = 35.0).block()
        listOf(
            r("Jeanne Martin", "Ch. 12", "Étage 1") {
                copy(isInZone = false, exitedAt = now - 7 * 60_000, alertState = AlertState.ACTIVE, lastLatitude = 47.004,
                    distanceFromCenterMeters = 445.0, riskLevel = 2, avatarColorHex = "#E53935")
            },
            r("Paul Lefèvre", "Ch. 3", "Étage 1") {
                copy(isInZone = false, exitedAt = now - 3 * 60_000, alertState = AlertState.HANDLING, alertHandledBy = "Paula",
                    lastLatitude = 46.997, lastLongitude = -1.003, distanceFromCenterMeters = 390.0)
            },
            r("Marie Dubois", "Ch. 7", "Étage 2") { copy(avatarColorHex = "#8E24AA") },
            r("André Petit", "Ch. 8", "Étage 2") { copy(lastUpdatedTime = now - 50 * 60_000, isInDeepSleep = true) },
            r("Lucie Bernard", "Ch. 9", "Étage 2") { copy(trackerId = null, lastLatitude = null, lastLongitude = null) },
            r("Henri Moreau", "Ch. 10", "Étage 1") { copy(lastBattery = 12, avatarColorHex = "#FB8C00") },
            r("Odette Roux", "Ch. 11", "Étage 1") { copy(pausedUntil = now + 2 * 3_600_000, pauseReason = "Sortie famille") },
            r("Gaston Blanc", "Ch. 14", "Étage 2") { copy(isTrackingActive = false) }
        ).forEach { db.residentDao().insertResident(it) }
        db.alertEventDao().insertAlert(AlertEvent(residentId = 1, residentName = "Jeanne Martin", alertType = AlertType.EXIT_ZONE, distanceMeters = 445.0))
        db.alertEventDao().insertAlert(AlertEvent(residentId = 6, residentName = "Henri Moreau", alertType = AlertType.LOW_BATTERY, details = "Batterie à 12 %"))
        db.alertEventDao().insertAlert(AlertEvent(residentId = 2, residentName = "Paul Lefèvre", alertType = AlertType.HANDLING, isAcknowledged = true,
            acknowledgedBy = "Paula", details = "Pris en charge par Paula"))
        MonitoringHealth.update { it.copy(serviceRunning = true, lastCycleAt = now - 8_000, activeCount = 7, okCount = 6, errorCount = 1, nextIntervalSeconds = 15) }
        app.preferences.setStaffName("Paula")
    }

    /** Le singleton Room est lié à l'Application du test : on le libère pour le test suivant. */
    @org.junit.After
    fun resetDatabaseSingleton() {
        runCatching { app.database.close() }
        fr.alerteresidents.data.local.AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
    }

    /**
     * Dialogues contenant des champs de saisie : sous Robolectric, un champ de texte dans un
     * dialogue empêche Compose de devenir « inactif » ; on capture donc avec l'horloge figée.
     */
    private fun dialogShot(name: String, closeLabel: String, open: () -> Unit) {
        compose.mainClock.autoAdvance = false
        open()
        step()
        shot(name, idle = false)
        compose.onNodeWithText(closeLabel).performSemanticsAction(SemanticsActions.OnClick)
        step()
        compose.mainClock.autoAdvance = true
    }

    /** Avance l'horloge Compose et la boucle principale Robolectric (création des fenêtres de dialogue). */
    private fun step() = repeat(10) {
        compose.mainClock.advanceTimeBy(200)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Dessine la fenêtre de l'activité, puis le dialogue éventuellement ouvert par-dessus (centré). */
    private fun shot(name: String, idle: Boolean = true) {
        if (idle) compose.waitForIdle()
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val root = compose.activity.window.decorView
            bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp!!)
            root.draw(canvas)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            if (dialog != null && dialog.isShowing) {
                canvas.drawColor(0x88000000.toInt())
                val dv = dialog.window!!.decorView
                canvas.save()
                canvas.translate((root.width - dv.width) / 2f, (root.height - dv.height) / 2f)
                dv.draw(canvas)
                canvas.restore()
            }
        }
        val dir = File("build/ui-screenshots").apply { mkdirs() }
        File(dir, "${name}_$suffix.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun allScreensRender() {
        try {
            scenario()
        } catch (e: Throwable) {
            runCatching { shot("zz_echec") }
            throw e
        }
    }

    private fun scenario() {
        val firstId = runBlocking { app.database.residentDao().getAllResidentsOnce().first { it.name == "Henri Moreau" }.id }
        waitForText("Jeanne Martin")
        shot("01_tableau_de_bord")

        // Alarme réelle : la pop-up liste les résidents concernés
        val zone = runBlocking { app.database.facilityZoneDao().getFacilityZoneOnce()!! }
        val residents = runBlocking { app.database.residentDao().getAllResidentsOnce() }
        compose.runOnUiThread {
            residents.filter { !it.isInZone }.forEach { app.soundAlertManager.playZoneExitAlarm(it, zone.copy(soundAlertsEnabled = false)) }
        }
        waitForText("SORTIES DE ZONE")
        shot("02_alarme_multi")
        compose.onNodeWithTag("btn_alarm_popup_silence").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("SORTIES DE ZONE", substring = true).fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithTag("alert_banner_header").performClick() // replie le bandeau
        compose.runOnUiThread { app.preferences.setViewMode(DashboardViewMode.COMPACT) }
        waitForTag("resident_row_$firstId")
        shot("03_liste_compacte")
        // Action d'accessibilité : le snackbar peut recouvrir le bouton
        dialogShot("09_fiche_resident", "Annuler") {
            compose.onNodeWithTag("fab_add_resident").performSemanticsAction(SemanticsActions.OnClick)
        }

        // Sélection multiple (appui long) puis sortie accompagnée
        compose.onNodeWithTag("resident_row_$firstId").performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("sélectionné")
        dialogShot("11_sortie_accompagnee", "Annuler") {
            compose.onNodeWithText("Sortie").performSemanticsAction(SemanticsActions.OnClick)
        }

        compose.runOnUiThread { app.preferences.setViewMode(DashboardViewMode.GRID); app.preferences.setGroupByUnit(true) }
        waitForTag("resident_tile_$firstId")
        shot("04_grille_par_unite")
        compose.runOnUiThread { app.preferences.setViewMode(DashboardViewMode.DETAILED); app.preferences.setGroupByUnit(false) }

        compose.onNodeWithTag("nav_tab_alerts").performClick()
        waitForText("Journal des alertes")
        shot("05_journal")

        compose.onNodeWithTag("nav_tab_settings").performClick()
        waitForText("Comptes Weenect")
        shot("06_parametres")
        dialogShot("10_comptes_weenect", "Fermer") {
            compose.onNodeWithText("Gérer les comptes Weenect").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        }

        compose.onNodeWithTag("nav_tab_dashboard").performClick()
        compose.onNodeWithTag("health_header").performClick()
        waitForText("Prise de poste")
        shot("07_etat_surveillance")

        compose.mainClock.autoAdvance = false // la carte contient une animation infinie
        compose.onNodeWithTag("nav_tab_map").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("interactive_compass_map").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        shot("08_carte")
    }
}

/** Mêmes écrans en thème sombre (contrastes). */
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-xhdpi")
class UiSmokeDarkTest : UiSmokeTest() {
    override val suffix = "dark"
}
