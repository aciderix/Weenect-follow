package fr.alerteresidents.util

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.security.PassphraseCrypto
import okio.Buffer

/** Compte Weenect tel qu'exporté : [ref] relie les résidents à leur compte dans le fichier. */
data class BackupAccount(val ref: Long, val label: String, val username: String)

data class BackupData(
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
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

/**
 * Format de sauvegarde partagé entre Android et Windows (même fichier .json).
 * Les mots de passe Weenect ne sont inclus que chiffrés par un code (PBKDF2 + AES-GCM).
 */
object BackupCodec {
    const val SCHEMA_VERSION = 2

    private val mapAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun createBackupJson(
        facilityZone: FacilityZone,
        residents: List<Resident>,
        exportedBy: String = "Équipe soignante",
        accounts: List<Pair<WeenectAccount, String?>> = emptyList(),
        passphrase: String? = null,
        appName: String = "AlerteResidents"
    ): String {
        val secrets = linkedMapOf<String, Any?>()
        val accountList = accounts.map { (a, pwd) ->
            if (pwd != null) secrets[a.id.toString()] = pwd
            linkedMapOf<String, Any?>("ref" to a.id, "label" to a.label, "username" to a.username)
        }
        val root = linkedMapOf<String, Any?>(
            "schemaVersion" to SCHEMA_VERSION,
            "app" to appName,
            "appVersion" to "2.0",
            "exportTimestamp" to System.currentTimeMillis(),
            "exportedBy" to exportedBy,
            "facilityZone" to zoneToMap(facilityZone),
            "accounts" to accountList
        )
        if (!passphrase.isNullOrBlank() && secrets.isNotEmpty()) {
            root["encryptedSecrets"] = PassphraseCrypto.encrypt(mapAdapter.toJson(secrets), passphrase)
        }
        root["residents"] = residents.map { res ->
            linkedMapOf<String, Any?>(
                "name" to res.name,
                "roomNumber" to res.roomNumber,
                "avatarColorHex" to res.avatarColorHex
            ).apply {
                if (res.accountId != null) put("accountRef", res.accountId)
                if (res.trackerId != null) put("trackerId", res.trackerId)
                if (res.trackerName != null) put("trackerName", res.trackerName)
                put("emergencyContact", res.emergencyContact)
                put("notes", res.notes)
                put("isTrackingActive", res.isTrackingActive)
                put("unit", res.unit)
                put("riskLevel", res.riskLevel)
            }
        }
        val buffer = Buffer()
        com.squareup.moshi.JsonWriter.of(buffer).use { w ->
            w.indent = "  "
            mapAdapter.toJson(w, root)
        }
        return buffer.readUtf8()
    }

    fun parseBackupJson(jsonString: String): BackupData? = try {
        val root = mapAdapter.fromJson(jsonString) ?: throw IllegalArgumentException("vide")
        @Suppress("UNCHECKED_CAST")
        val z = root["facilityZone"] as? Map<String, Any?> ?: throw IllegalArgumentException("zone manquante")
        val zone = zoneFromMap(z)
        require(zone.centerLatitude in -90.0..90.0 && zone.centerLongitude in -180.0..180.0) { "Coordonnées invalides" }
        require(zone.radiusMeters > 0) { "Rayon invalide" }

        @Suppress("UNCHECKED_CAST")
        val accounts = (root["accounts"] as? List<Map<String, Any?>>).orEmpty().map {
            BackupAccount(it.long("ref") ?: error("ref"), it.str("label", ""), it["username"] as? String ?: error("username"))
        }
        @Suppress("UNCHECKED_CAST")
        val residents = (root["residents"] as? List<Map<String, Any?>>).orEmpty().mapIndexed { i, r ->
            Resident(
                id = 0,
                name = r.str("name", "Résident ${i + 1}"),
                roomNumber = r.str("roomNumber", ""),
                avatarColorHex = r.str("avatarColorHex", "#1E88E5"),
                weenectUsername = r.str("weenectUsername", ""),
                weenectPassword = r.str("weenectPassword", ""),
                accountId = r.long("accountRef"),
                trackerId = r.long("trackerId"),
                trackerName = r["trackerName"] as? String,
                emergencyContact = r.str("emergencyContact", ""),
                notes = r.str("notes", ""),
                isTrackingActive = r.bool("isTrackingActive", true),
                unit = r.str("unit", ""),
                riskLevel = r.int("riskLevel", 0).coerceIn(0, 2)
            )
        }
        BackupData(
            schemaVersion = root.int("schemaVersion", 1),
            appVersion = root.str("appVersion", "1.0"),
            exportTimestamp = root.long("exportTimestamp") ?: System.currentTimeMillis(),
            exportedBy = root.str("exportedBy", "Équipe soignante"),
            facilityZone = zone,
            residents = residents,
            accounts = accounts,
            encryptedSecrets = root["encryptedSecrets"] as? String
        )
    } catch (e: Exception) {
        null
    }

    /** Déchiffre les mots de passe d'un export : ref → mot de passe, ou null si le code est faux. */
    fun decryptSecrets(backup: BackupData, passphrase: String): Map<Long, String>? {
        val blob = backup.encryptedSecrets ?: return emptyMap()
        val json = PassphraseCrypto.decrypt(blob, passphrase) ?: return null
        val map = mapAdapter.fromJson(json) ?: return null
        return map.entries.associate { it.key.toLong() to it.value.toString() }
    }

    /** Zone ↔ objet JSON (sauvegarde et synchronisation Supabase). */
    fun zoneToMap(zone: FacilityZone): Map<String, Any?> = linkedMapOf(
        "name" to zone.name,
        "address" to zone.address,
        "centerLatitude" to zone.centerLatitude,
        "centerLongitude" to zone.centerLongitude,
        "radiusMeters" to zone.radiusMeters,
        "isZoneActive" to zone.isZoneActive,
        "zoneType" to zone.zoneType,
        "polygonPointsJson" to zone.polygonPointsJson,
        "soundAlertsEnabled" to zone.soundAlertsEnabled,
        "vibrateAlertsEnabled" to zone.vibrateAlertsEnabled,
        "refreshIntervalSeconds" to zone.refreshIntervalSeconds,
        "extraZonesJson" to zone.extraZonesJson,
        "nightModeEnabled" to zone.nightModeEnabled,
        "nightStartHour" to zone.nightStartHour,
        "nightEndHour" to zone.nightEndHour,
        "nightRefreshIntervalSeconds" to zone.nightRefreshIntervalSeconds
    )

    fun zoneFromMap(z: Map<String, Any?>): FacilityZone {
        val d = FacilityZone()
        return FacilityZone(
            id = 1,
            name = z.str("name", d.name),
            address = z.str("address", ""),
            centerLatitude = z.dbl("centerLatitude", d.centerLatitude),
            centerLongitude = z.dbl("centerLongitude", d.centerLongitude),
            radiusMeters = z.dbl("radiusMeters", 150.0),
            isZoneActive = z.bool("isZoneActive", true),
            zoneType = z.str("zoneType", "CIRCLE"),
            polygonPointsJson = z.str("polygonPointsJson", ""),
            soundAlertsEnabled = z.bool("soundAlertsEnabled", true),
            vibrateAlertsEnabled = z.bool("vibrateAlertsEnabled", true),
            refreshIntervalSeconds = z.int("refreshIntervalSeconds", 15),
            extraZonesJson = z.str("extraZonesJson", ""),
            nightModeEnabled = z.bool("nightModeEnabled", false),
            nightStartHour = z.int("nightStartHour", d.nightStartHour),
            nightEndHour = z.int("nightEndHour", d.nightEndHour),
            nightRefreshIntervalSeconds = z.int("nightRefreshIntervalSeconds", d.nightRefreshIntervalSeconds)
        )
    }

    private fun Map<String, Any?>.str(k: String, def: String) = (this[k] as? String) ?: def
    private fun Map<String, Any?>.dbl(k: String, def: Double) = (this[k] as? Number)?.toDouble() ?: def
    private fun Map<String, Any?>.int(k: String, def: Int) = (this[k] as? Number)?.toInt() ?: def
    private fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.bool(k: String, def: Boolean) = (this[k] as? Boolean) ?: def
}
