package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupData(
    val schemaVersion: Int = 1,
    val appVersion: String = "1.0",
    val exportTimestamp: Long = System.currentTimeMillis(),
    val exportedBy: String = "Équipe Soignante",
    val facilityZone: FacilityZone,
    val residents: List<Resident>
)

object ConfigBackupManager {

    private const val SCHEMA_VERSION = 1

    /**
     * Génère la chaîne JSON complète et le fichier de sauvegarde prêt au partage.
     */
    fun createBackupJson(
        facilityZone: FacilityZone,
        residents: List<Resident>,
        exportedBy: String = "Équipe Soignante"
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("app", "SecuriResident")
        root.put("appVersion", "1.0")
        root.put("exportTimestamp", System.currentTimeMillis())
        root.put("exportedBy", exportedBy)

        // 1. Établissement & Périmètre
        val zoneObj = JSONObject().apply {
            put("id", facilityZone.id)
            put("name", facilityZone.name)
            put("address", facilityZone.address)
            put("centerLatitude", facilityZone.centerLatitude)
            put("centerLongitude", facilityZone.centerLongitude)
            put("radiusMeters", facilityZone.radiusMeters)
            put("isZoneActive", facilityZone.isZoneActive)
            put("zoneType", facilityZone.zoneType)
            put("polygonPointsJson", facilityZone.polygonPointsJson)
            put("soundAlertsEnabled", facilityZone.soundAlertsEnabled)
            put("vibrateAlertsEnabled", facilityZone.vibrateAlertsEnabled)
            put("refreshIntervalSeconds", facilityZone.refreshIntervalSeconds)
        }
        root.put("facilityZone", zoneObj)

        // 2. Résidents & Balises
        val residentsArray = JSONArray()
        for (res in residents) {
            val resObj = JSONObject().apply {
                put("name", res.name)
                put("roomNumber", res.roomNumber)
                put("avatarColorHex", res.avatarColorHex)
                put("weenectUsername", res.weenectUsername)
                put("weenectPassword", res.weenectPassword)
                if (res.trackerId != null) put("trackerId", res.trackerId)
                if (res.trackerName != null) put("trackerName", res.trackerName)
                put("emergencyContact", res.emergencyContact)
                put("notes", res.notes)
                put("isTrackingActive", res.isTrackingActive)
            }
            residentsArray.put(resObj)
        }
        root.put("residents", residentsArray)

        return root.toString(2)
    }

    /**
     * Exporte la configuration dans un fichier et déclenche la feuille de partage Android (Share Sheet).
     */
    fun exportAndShare(
        context: Context,
        facilityZone: FacilityZone,
        residents: List<Resident>,
        exportedBy: String = "Équipe Soignante"
    ): Boolean {
        return try {
            val jsonContent = createBackupJson(facilityZone, residents, exportedBy)
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }

            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val sanitizedFacility = facilityZone.name.replace(Regex("[^a-zA-Z0-9_]"), "_").take(20)
            val file = File(exportsDir, "config_${sanitizedFacility}_$dateStr.json")

            FileOutputStream(file).use { out ->
                out.write(jsonContent.toByteArray(Charsets.UTF_8))
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Configuration SécuriRésident - ${facilityZone.name}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Fichier de configuration SécuriRésident pour l'établissement ${facilityZone.name} (${residents.size} résident(s)).\n\nÀ ouvrir ou importer directement dans l'application SécuriRésident sur le nouveau smartphone."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Transférer la configuration à un collègue")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Parse et valide un texte JSON de configuration.
     */
    fun parseBackupJson(jsonString: String): BackupData? {
        return try {
            val root = JSONObject(jsonString)

            val schemaVersion = root.optInt("schemaVersion", 1)
            val appVersion = root.optString("appVersion", "1.0")
            val exportTimestamp = root.optLong("exportTimestamp", System.currentTimeMillis())
            val exportedBy = root.optString("exportedBy", "Équipe Soignante")

            // Parse Zone
            val zoneObj = root.getJSONObject("facilityZone")
            val zone = FacilityZone(
                id = 1,
                name = zoneObj.optString("name", "Établissement"),
                address = zoneObj.optString("address", ""),
                centerLatitude = zoneObj.optDouble("centerLatitude", 47.1787),
                centerLongitude = zoneObj.optDouble("centerLongitude", -1.6192),
                radiusMeters = zoneObj.optDouble("radiusMeters", 150.0),
                isZoneActive = zoneObj.optBoolean("isZoneActive", true),
                zoneType = zoneObj.optString("zoneType", "CIRCLE"),
                polygonPointsJson = zoneObj.optString("polygonPointsJson", ""),
                soundAlertsEnabled = zoneObj.optBoolean("soundAlertsEnabled", true),
                vibrateAlertsEnabled = zoneObj.optBoolean("vibrateAlertsEnabled", true),
                refreshIntervalSeconds = zoneObj.optInt("refreshIntervalSeconds", 15)
            )

            // Parse Residents
            val residentsList = mutableListOf<Resident>()
            val residentsArray = root.optJSONArray("residents") ?: JSONArray()
            for (i in 0 until residentsArray.length()) {
                val rObj = residentsArray.getJSONObject(i)
                val resident = Resident(
                    id = 0, // Nouveau ID auto-généré dans Room
                    name = rObj.optString("name", "Résident $i"),
                    roomNumber = rObj.optString("roomNumber", ""),
                    avatarColorHex = rObj.optString("avatarColorHex", "#1E88E5"),
                    weenectUsername = rObj.optString("weenectUsername", ""),
                    weenectPassword = rObj.optString("weenectPassword", ""),
                    trackerId = if (rObj.has("trackerId") && !rObj.isNull("trackerId")) rObj.getLong("trackerId") else null,
                    trackerName = if (rObj.has("trackerName") && !rObj.isNull("trackerName")) rObj.getString("trackerName") else null,
                    emergencyContact = rObj.optString("emergencyContact", ""),
                    notes = rObj.optString("notes", ""),
                    isTrackingActive = rObj.optBoolean("isTrackingActive", true)
                )
                residentsList.add(resident)
            }

            BackupData(
                schemaVersion = schemaVersion,
                appVersion = appVersion,
                exportTimestamp = exportTimestamp,
                exportedBy = exportedBy,
                facilityZone = zone,
                residents = residentsList
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Lit le contenu textuel d'un fichier sélectionné via un Uri.
     */
    fun readJsonFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
