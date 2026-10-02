package fr.alerteresidents.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.security.PassphraseCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compte Weenect tel qu'exporté : [ref] relie les résidents à leur compte dans le fichier. */
data class BackupAccount(val ref: Long, val label: String, val username: String)

data class BackupData(
    val schemaVersion: Int = ConfigBackupManager.SCHEMA_VERSION,
    val appVersion: String = "2.0",
    val exportTimestamp: Long = System.currentTimeMillis(),
    val exportedBy: String = "Équipe soignante",
    val facilityZone: FacilityZone,
    /** `accountId` contient la référence [BackupAccount.ref] du fichier, pas un id de la base. */
    val residents: List<Resident>,
    val accounts: List<BackupAccount> = emptyList(),
    /** Mots de passe chiffrés par un code (null : export sans mots de passe). */
    val encryptedSecrets: String? = null
) {
    val hasEncryptedPasswords: Boolean get() = encryptedSecrets != null
    /** Ancien format (v1) : identifiants en clair dans chaque fiche. */
    val hasLegacyPlaintextCredentials: Boolean get() = residents.any { it.weenectPassword.isNotBlank() }
}

object ConfigBackupManager {

    const val SCHEMA_VERSION = 2

    /**
     * Génère le JSON de configuration. Les mots de passe Weenect ne sont inclus que si un
     * [passphrase] est fourni, et sont alors chiffrés (PBKDF2 + AES-GCM).
     */
    fun createBackupJson(
        facilityZone: FacilityZone,
        residents: List<Resident>,
        exportedBy: String = "Équipe soignante",
        accounts: List<Pair<WeenectAccount, String?>> = emptyList(),
        passphrase: String? = null
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("app", "AlerteResidents")
        root.put("appVersion", "2.0")
        root.put("exportTimestamp", System.currentTimeMillis())
        root.put("exportedBy", exportedBy)

        root.put("facilityZone", JSONObject().apply {
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
            put("extraZonesJson", facilityZone.extraZonesJson)
            put("nightModeEnabled", facilityZone.nightModeEnabled)
            put("nightStartHour", facilityZone.nightStartHour)
            put("nightEndHour", facilityZone.nightEndHour)
            put("nightRefreshIntervalSeconds", facilityZone.nightRefreshIntervalSeconds)
        })

        val accountsArray = JSONArray()
        val secrets = JSONObject()
        for ((account, password) in accounts) {
            accountsArray.put(JSONObject().put("ref", account.id).put("label", account.label).put("username", account.username))
            if (password != null) secrets.put(account.id.toString(), password)
        }
        root.put("accounts", accountsArray)
        if (!passphrase.isNullOrBlank() && secrets.length() > 0) {
            root.put("encryptedSecrets", PassphraseCrypto.encrypt(secrets.toString(), passphrase))
        }

        val residentsArray = JSONArray()
        for (res in residents) {
            residentsArray.put(JSONObject().apply {
                put("name", res.name)
                put("roomNumber", res.roomNumber)
                put("avatarColorHex", res.avatarColorHex)
                if (res.accountId != null) put("accountRef", res.accountId)
                if (res.trackerId != null) put("trackerId", res.trackerId)
                if (res.trackerName != null) put("trackerName", res.trackerName)
                put("emergencyContact", res.emergencyContact)
                put("notes", res.notes)
                put("isTrackingActive", res.isTrackingActive)
                put("unit", res.unit)
                put("riskLevel", res.riskLevel)
            })
        }
        root.put("residents", residentsArray)
        return root.toString(2)
    }

    fun exportAndShare(context: Context, json: String, facilityName: String, residentCount: Int): Boolean = try {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(Date())
        val sanitized = facilityName.replace(Regex("[^a-zA-Z0-9_]"), "_").take(20)
        val uri = writeExport(context, "config_${sanitized}_$dateStr.json", json)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Configuration Alerte Résidents - $facilityName")
            putExtra(
                Intent.EXTRA_TEXT,
                "Configuration Alerte Résidents pour $facilityName ($residentCount résident(s)). " +
                    "À importer dans l'application sur le nouveau téléphone."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Transférer la configuration").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Écrit un fichier dans le cache d'export et renvoie son URI partageable. */
    fun writeExport(context: Context, fileName: String, content: String): Uri =
        writeExportBytes(context, fileName, content.toByteArray(Charsets.UTF_8))

    fun writeExportBytes(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun parseBackupJson(jsonString: String): BackupData? {
        return try {
            val root = JSONObject(jsonString)
            val schemaVersion = root.optInt("schemaVersion", 1)
            val zoneObj = root.getJSONObject("facilityZone")
            val defaults = FacilityZone()
            val zone = FacilityZone(
                id = 1,
                name = zoneObj.optString("name", defaults.name),
                address = zoneObj.optString("address", ""),
                centerLatitude = zoneObj.optDouble("centerLatitude", defaults.centerLatitude),
                centerLongitude = zoneObj.optDouble("centerLongitude", defaults.centerLongitude),
                radiusMeters = zoneObj.optDouble("radiusMeters", 150.0),
                isZoneActive = zoneObj.optBoolean("isZoneActive", true),
                zoneType = zoneObj.optString("zoneType", "CIRCLE"),
                polygonPointsJson = zoneObj.optString("polygonPointsJson", ""),
                soundAlertsEnabled = zoneObj.optBoolean("soundAlertsEnabled", true),
                vibrateAlertsEnabled = zoneObj.optBoolean("vibrateAlertsEnabled", true),
                refreshIntervalSeconds = zoneObj.optInt("refreshIntervalSeconds", 15),
                extraZonesJson = zoneObj.optString("extraZonesJson", ""),
                nightModeEnabled = zoneObj.optBoolean("nightModeEnabled", false),
                nightStartHour = zoneObj.optInt("nightStartHour", defaults.nightStartHour),
                nightEndHour = zoneObj.optInt("nightEndHour", defaults.nightEndHour),
                nightRefreshIntervalSeconds = zoneObj.optInt("nightRefreshIntervalSeconds", defaults.nightRefreshIntervalSeconds)
            )
            require(zone.centerLatitude in -90.0..90.0 && zone.centerLongitude in -180.0..180.0) { "Coordonnées invalides" }
            require(zone.radiusMeters > 0) { "Rayon invalide" }

            val accounts = mutableListOf<BackupAccount>()
            root.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    accounts.add(BackupAccount(o.getLong("ref"), o.optString("label"), o.getString("username")))
                }
            }

            val residentsList = mutableListOf<Resident>()
            val residentsArray = root.optJSONArray("residents") ?: JSONArray()
            for (i in 0 until residentsArray.length()) {
                val rObj = residentsArray.getJSONObject(i)
                residentsList.add(
                    Resident(
                        id = 0,
                        name = rObj.optString("name", "Résident ${i + 1}"),
                        roomNumber = rObj.optString("roomNumber", ""),
                        avatarColorHex = rObj.optString("avatarColorHex", "#1E88E5"),
                        // v1 : identifiants en clair (convertis en comptes chiffrés à l'import)
                        weenectUsername = rObj.optString("weenectUsername", ""),
                        weenectPassword = rObj.optString("weenectPassword", ""),
                        accountId = if (rObj.has("accountRef") && !rObj.isNull("accountRef")) rObj.getLong("accountRef") else null,
                        trackerId = if (rObj.has("trackerId") && !rObj.isNull("trackerId")) rObj.getLong("trackerId") else null,
                        trackerName = if (rObj.has("trackerName") && !rObj.isNull("trackerName")) rObj.getString("trackerName") else null,
                        emergencyContact = rObj.optString("emergencyContact", ""),
                        notes = rObj.optString("notes", ""),
                        isTrackingActive = rObj.optBoolean("isTrackingActive", true),
                        unit = rObj.optString("unit", ""),
                        riskLevel = rObj.optInt("riskLevel", 0).coerceIn(0, 2)
                    )
                )
            }

            BackupData(
                schemaVersion = schemaVersion,
                appVersion = root.optString("appVersion", "1.0"),
                exportTimestamp = root.optLong("exportTimestamp", System.currentTimeMillis()),
                exportedBy = root.optString("exportedBy", "Équipe soignante"),
                facilityZone = zone,
                residents = residentsList,
                accounts = accounts,
                encryptedSecrets = if (root.has("encryptedSecrets")) root.getString("encryptedSecrets") else null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Déchiffre les mots de passe d'un export : ref → mot de passe, ou null si le code est faux. */
    fun decryptSecrets(backup: BackupData, passphrase: String): Map<Long, String>? {
        val blob = backup.encryptedSecrets ?: return emptyMap()
        val json = PassphraseCrypto.decrypt(blob, passphrase) ?: return null
        val obj = JSONObject(json)
        return obj.keys().asSequence().associate { it.toLong() to obj.getString(it) }
    }

    fun readJsonFromUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
