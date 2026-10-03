package fr.alerteresidents.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.store.ConfigStore
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.security.PassphraseCrypto
import fr.alerteresidents.util.BackupCodec
import fr.alerteresidents.util.DateParsing
import okio.ByteString.Companion.encodeUtf8
import java.util.UUID
import java.util.logging.Logger

/**
 * Synchronisation automatique des fiches résidents, des comptes Weenect et de la zone entre
 * tous les appareils reliés au même projet Supabase (tables de 20261003120000_sync_configuration.sql).
 *
 * Principe, à chaque cycle :
 * 1. lecture des changements distants (tout au premier cycle) et application ici, sauf sur une
 *    fiche modifiée localement et pas encore envoyée (le changement local gagne) ;
 * 2. envoi des fiches modifiées ici depuis le dernier envoi, et des fiches retirées.
 * Un « instantané » (empreinte de chaque fiche au dernier échange) permet de savoir ce qui a
 * changé localement, quelle que soit la façon dont la fiche a été modifiée (écran, import…).
 *
 * Au premier raccordement d'un appareil déjà configuré, ses fiches sont rattachées aux fiches
 * partagées de même balise (ou même identifiant Weenect pour les comptes) au lieu d'être dupliquées.
 *
 * Mots de passe Weenect : chiffrés par la phrase secrète de l'établissement avant l'envoi, et
 * déchiffrés à la réception (puis rechiffrés avec la clé de l'appareil). Sans phrase secrète, les
 * comptes sont partagés sans mot de passe.
 */
internal class ConfigSync(
    private val store: KeyValueStore,
    private val cipher: CredentialCipher,
    private val config: ConfigStore
) {
    private val log = Logger.getLogger("ConfigSync")
    private val moshi = Moshi.Builder().build()
    private val dtoAdapter = moshi.adapter(ConfigSyncDto::class.java)
    private val stringAdapter = moshi.adapter(String::class.java)
    private val snapshotAdapter: JsonAdapter<Map<String, String>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))

    /** Fiches partagées connues depuis le démarrage (toutes après la première lecture). */
    private val remoteResidents = HashMap<String, RemoteResidentDto>()
    private val remoteAccounts = HashMap<String, RemoteAccountDto>()
    private var cursor: String? = null
    private var zoneStamp: String? = null

    var sharedResidents = 0
        private set
    /** Des mots de passe partagés n'ont pas pu être déchiffrés (phrase secrète absente ou différente). */
    var passphraseNeeded = false
        private set
    val passphraseSet: Boolean get() = store.get(K_PASSPHRASE) != null

    private fun passphrase(): String? = store.get(K_PASSPHRASE)?.let { cipher.decrypt(it) }

    /** Oublie l'état d'échange (changement de projet) : tout sera relu et rattaché au prochain cycle. */
    fun reset(clearPassphrase: Boolean) {
        store.put(K_SNAPSHOT, null)
        if (clearPassphrase) store.put(K_PASSPHRASE, null)
        remoteResidents.clear()
        remoteAccounts.clear()
        cursor = null
        zoneStamp = null
        passphraseNeeded = false
    }

    /**
     * Enregistre la phrase secrète de l'établissement après l'avoir vérifiée (ou l'avoir fixée si
     * c'est le premier appareil). Les mots de passe seront ensuite renvoyés et relus.
     */
    suspend fun setPassphrase(c: SupabaseClient, token: String, passphrase: String) {
        require(passphrase.length >= MIN_PASSPHRASE) { "La phrase secrète doit faire au moins $MIN_PASSPHRASE caractères" }
        val proposed = PassphraseCrypto.encrypt(CHECK_MARKER, passphrase)
        val raw = c.rpc("init_passphrase_check", mapOf("p_check" to proposed), token)
        val current = stringAdapter.fromJson(raw) ?: proposed
        if (PassphraseCrypto.decrypt(current, passphrase) != CHECK_MARKER) {
            throw SupabaseException("Ce n'est pas la phrase secrète de l'établissement (celle saisie sur le premier appareil)")
        }
        store.put(K_PASSPHRASE, cipher.encrypt(passphrase))
        // Renvoyer les mots de passe et relire ceux des autres.
        saveSnapshot(loadSnapshot().filterKeys { !it.startsWith(ACCOUNT) })
        cursor = null
        passphraseNeeded = false
    }

    suspend fun sync(c: SupabaseClient, token: String, by: String) {
        val raw = c.rpc("sync_config", mapOf("p_since" to cursor), token)
        val dto = dtoAdapter.fromJson(raw) ?: throw SupabaseException("Réponse illisible (configuration)")
        val snapshot = loadSnapshot().toMutableMap()
        val pass = passphrase()
        dto.accounts.forEach { remoteAccounts[it.id] = it }
        dto.residents.forEach { remoteResidents[it.id] = it }

        // 1. Changements distants → cet appareil
        var neededPassphrase = false
        for (ra in dto.accounts) if (!applyAccount(ra, snapshot, pass)) neededPassphrase = true
        for (rr in dto.residents) applyResident(rr, snapshot)
        applyZone(dto, snapshot)
        if (cursor == null) passphraseNeeded = neededPassphrase else if (neededPassphrase) passphraseNeeded = true
        saveSnapshot(snapshot)

        // 2. Changements locaux → partage
        pushAccounts(c, token, by, snapshot, pass)
        pushResidents(c, token, by, snapshot)
        pushZone(c, token, by, snapshot)
        saveSnapshot(snapshot)

        sharedResidents = remoteResidents.values.count { !it.removed }
        cursor = DateParsing.parseIso(dto.now)?.let { DateParsing.formatIso(it - 30_000L) }
    }

    // ------------------------------------------------------------------------------------------
    // Comptes Weenect
    // ------------------------------------------------------------------------------------------

    /** false si le mot de passe partagé n'a pas pu être déchiffré. */
    private suspend fun applyAccount(ra: RemoteAccountDto, snapshot: MutableMap<String, String>, pass: String?): Boolean {
        val key = ACCOUNT + ra.id
        val locals = config.accounts()
        var local = locals.find { it.syncId == ra.id }
        if (local == null && !ra.removed) {
            local = locals.find { it.syncId == null && it.username.equals(ra.username, ignoreCase = true) }
                ?.copy(syncId = ra.id)?.also { config.updateAccount(it) }
        }
        if (local != null && snapshot[key] != null && snapshot[key] != hash(local)) return true // modifié ici : envoyé ensuite
        if (ra.removed) {
            if (local != null) config.removeAccount(local)
            snapshot.remove(key)
            return true
        }
        val plain = ra.passwordEnc?.let { enc -> pass?.let { PassphraseCrypto.decrypt(enc, it) } }
        val encrypted = plain?.let { cipher.encrypt(it) }
        val saved = if (local == null) {
            val acc = WeenectAccount(label = ra.label, username = ra.username, encryptedPassword = encrypted ?: "", syncId = ra.id)
            acc.copy(id = config.insertAccount(acc))
        } else {
            local.copy(label = ra.label, username = ra.username, encryptedPassword = encrypted ?: local.encryptedPassword)
                .also { if (it != local) config.updateAccount(it) }
        }
        snapshot[key] = hash(saved)
        return ra.passwordEnc == null || plain != null || cipher.decrypt(saved.encryptedPassword).orEmpty().isNotEmpty()
    }

    private suspend fun pushAccounts(c: SupabaseClient, token: String, by: String, snapshot: MutableMap<String, String>, pass: String?) {
        val locals = config.accounts()
        for (acc in locals) {
            var a = acc
            if (a.syncId == null) {
                val match = remoteAccounts.values.find { r ->
                    !r.removed && r.username.equals(a.username, ignoreCase = true) && locals.none { it.syncId == r.id }
                }
                a = a.copy(syncId = match?.id ?: UUID.randomUUID().toString())
                config.updateAccount(a)
                if (match != null) {
                    // Rattaché à un compte partagé : c'est lui qui fait foi.
                    applyAccount(match, snapshot, pass)
                    continue
                }
            }
            val key = ACCOUNT + a.syncId
            if (snapshot[key] == null && remoteAccounts.containsKey(a.syncId)) continue // rattaché, déjà appliqué
            if (snapshot[key] == hash(a)) continue
            val plain = cipher.decrypt(a.encryptedPassword)?.takeIf { it.isNotEmpty() }
            val body = linkedMapOf<String, Any?>("id" to a.syncId, "label" to a.label, "username" to a.username)
            if (pass != null && plain != null) body["password_enc"] = PassphraseCrypto.encrypt(plain, pass)
            c.rpc("save_account", mapOf("p" to body, "p_by" to by), token)
            snapshot[key] = hash(a)
        }
        // Comptes retirés ici
        val alive = config.accounts().mapNotNull { it.syncId }.toSet()
        for (key in snapshot.keys.filter { it.startsWith(ACCOUNT) }) {
            val id = key.removePrefix(ACCOUNT)
            if (id in alive) continue
            val known = remoteAccounts[id]
            c.rpc("save_account", mapOf("p" to mapOf("id" to id, "label" to known?.label.orEmpty(), "username" to known?.username.orEmpty(), "removed" to true), "p_by" to by), token)
            snapshot.remove(key)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Résidents
    // ------------------------------------------------------------------------------------------

    private suspend fun applyResident(rr: RemoteResidentDto, snapshot: MutableMap<String, String>) {
        val key = RESIDENT + rr.id
        val locals = config.residents()
        var local = locals.find { it.syncId == rr.id }
        if (local == null && !rr.removed) {
            local = locals.find { it.syncId == null && sameResident(it, rr) }
                ?.also { config.setResidentSyncId(it.id, rr.id) }?.copy(syncId = rr.id)
        }
        val accounts = config.accounts()
        if (local != null && snapshot[key] != null && snapshot[key] != hash(local, accounts)) return // modifié ici
        if (rr.removed) {
            if (local != null) config.removeResident(local.id)
            snapshot.remove(key)
            return
        }
        val accountId = rr.accountId?.let { id -> accounts.find { it.syncId == id }?.id }
        if (local == null) {
            val r = Resident(
                name = rr.name, roomNumber = rr.roomNumber, avatarColorHex = rr.avatarColor, accountId = accountId,
                trackerId = rr.trackerId, trackerName = rr.trackerName, emergencyContact = rr.emergencyContact,
                notes = rr.notes, isTrackingActive = rr.isTrackingActive, unit = rr.unit, riskLevel = rr.riskLevel,
                syncId = rr.id
            )
            val id = config.insertResident(r)
            snapshot[key] = hash(r.copy(id = id), accounts)
        } else {
            val profile = ResidentProfile(
                id = local.id, name = rr.name, roomNumber = rr.roomNumber, photoUri = local.photoUri,
                avatarColorHex = rr.avatarColor, accountId = accountId, trackerId = rr.trackerId,
                trackerName = rr.trackerName, emergencyContact = rr.emergencyContact, notes = rr.notes,
                isTrackingActive = rr.isTrackingActive, unit = rr.unit, riskLevel = rr.riskLevel
            )
            config.updateProfile(profile)
            snapshot[key] = hash(local.withProfile(profile), accounts)
        }
    }

    private suspend fun pushResidents(c: SupabaseClient, token: String, by: String, snapshot: MutableMap<String, String>) {
        val accounts = config.accounts()
        val locals = config.residents()
        for (res in locals) {
            var r = res
            if (r.syncId == null) {
                val match = remoteResidents.values.find { rr ->
                    !rr.removed && sameResident(r, rr) && locals.none { it.syncId == rr.id }
                }
                val id = match?.id ?: UUID.randomUUID().toString()
                config.setResidentSyncId(r.id, id)
                r = r.copy(syncId = id)
                if (match != null) {
                    applyResident(match, snapshot)
                    continue
                }
            }
            val key = RESIDENT + r.syncId
            if (snapshot[key] == null && remoteResidents.containsKey(r.syncId)) continue
            val h = hash(r, accounts)
            if (snapshot[key] == h) continue
            val accountSyncId = r.accountId?.let { id -> accounts.find { it.id == id }?.syncId }
            c.rpc("save_resident", mapOf("p" to residentBody(r, accountSyncId), "p_by" to by), token)
            snapshot[key] = h
        }
        val alive = config.residents().mapNotNull { it.syncId }.toSet()
        for (key in snapshot.keys.filter { it.startsWith(RESIDENT) }) {
            val id = key.removePrefix(RESIDENT)
            if (id in alive) continue
            val known = remoteResidents[id]
            c.rpc("save_resident", mapOf("p" to mapOf("id" to id, "name" to known?.name.orEmpty(), "tracker_id" to known?.trackerId, "removed" to true), "p_by" to by), token)
            snapshot.remove(key)
        }
    }

    /** Même balise ; sans balise des deux côtés, même nom et même chambre. */
    private fun sameResident(local: Resident, remote: RemoteResidentDto): Boolean = when {
        local.trackerId != null || remote.trackerId != null -> local.trackerId == remote.trackerId
        else -> local.name.trim().equals(remote.name.trim(), ignoreCase = true) &&
            local.roomNumber.trim().equals(remote.roomNumber.trim(), ignoreCase = true)
    }

    private fun residentBody(r: Resident, accountSyncId: String?) = linkedMapOf<String, Any?>(
        "id" to r.syncId, "name" to r.name, "room_number" to r.roomNumber, "unit" to r.unit,
        "avatar_color" to r.avatarColorHex, "tracker_id" to r.trackerId, "tracker_name" to r.trackerName,
        "account_id" to accountSyncId, "emergency_contact" to r.emergencyContact, "notes" to r.notes,
        "risk_level" to r.riskLevel, "is_tracking_active" to r.isTrackingActive, "removed" to false
    )

    // ------------------------------------------------------------------------------------------
    // Zone
    // ------------------------------------------------------------------------------------------

    private suspend fun applyZone(dto: ConfigSyncDto, snapshot: MutableMap<String, String>) {
        val remote = dto.zone ?: return
        if (dto.zoneUpdatedAt == zoneStamp) return
        val local = config.zone()
        val known = snapshot[ZONE]
        if (known != null && known != hash(local)) return // modifiée ici : envoyée ensuite
        val zone = runCatching { BackupCodec.zoneFromMap(remote) }.getOrNull() ?: return
        // Une zone jamais configurée (valeurs d'usine) ne remplace pas une vraie zone : la zone
        // locale sera partagée à la place.
        if (isFactoryDefault(zone) && !isFactoryDefault(local)) return
        if (zone.copy(id = local.id) != local) config.saveZone(zone.copy(id = local.id))
        snapshot[ZONE] = hash(zone.copy(id = local.id))
        zoneStamp = dto.zoneUpdatedAt
    }

    private suspend fun pushZone(c: SupabaseClient, token: String, by: String, snapshot: MutableMap<String, String>) {
        val local = config.zone()
        val h = hash(local)
        if (snapshot[ZONE] == h) return
        // Appareil neuf : sa zone d'usine n'a rien à partager.
        if (isFactoryDefault(local)) return
        if (snapshot[ZONE] == null && zoneStamp != null) return // zone partagée appliquée à ce cycle
        val raw = c.rpc("save_zone", mapOf("p_zone" to BackupCodec.zoneToMap(local), "p_by" to by), token)
        snapshot[ZONE] = h
        zoneStamp = runCatching { stringAdapter.fromJson(raw) }.getOrNull() ?: zoneStamp
    }

    /** Zone jamais configurée : valeurs de l'installation (nom, centre et rayon par défaut). */
    private fun isFactoryDefault(z: FacilityZone): Boolean {
        val d = FacilityZone()
        return z.name == d.name && z.centerLatitude == d.centerLatitude && z.centerLongitude == d.centerLongitude &&
            z.radiusMeters == d.radiusMeters && z.polygonPointsJson.isBlank() && z.extraZonesJson.isBlank()
    }

    // ------------------------------------------------------------------------------------------

    private fun loadSnapshot(): Map<String, String> =
        store.get(K_SNAPSHOT)?.let { runCatching { snapshotAdapter.fromJson(it) }.getOrNull() }.orEmpty()

    private fun saveSnapshot(map: Map<String, String>) = store.put(K_SNAPSHOT, snapshotAdapter.toJson(map))

    private fun digest(vararg parts: Any?): String =
        parts.joinToString("\u001F") { it?.toString() ?: "\u0000" }.encodeUtf8().sha256().hex().take(24)

    private fun hash(r: Resident, accounts: List<WeenectAccount>): String = digest(
        r.name, r.roomNumber, r.unit, r.avatarColorHex, r.trackerId, r.trackerName,
        r.accountId?.let { id -> accounts.find { it.id == id }?.syncId }, r.emergencyContact, r.notes,
        r.riskLevel, r.isTrackingActive
    )

    private fun hash(a: WeenectAccount): String = digest(a.label, a.username, a.encryptedPassword)

    private fun hash(z: FacilityZone): String = digest(BackupCodec.zoneToMap(z).entries.joinToString())

    private fun Resident.withProfile(p: ResidentProfile) = copy(
        name = p.name, roomNumber = p.roomNumber, photoUri = p.photoUri, avatarColorHex = p.avatarColorHex,
        accountId = p.accountId, trackerId = p.trackerId, trackerName = p.trackerName,
        emergencyContact = p.emergencyContact, notes = p.notes, isTrackingActive = p.isTrackingActive,
        unit = p.unit, riskLevel = p.riskLevel
    )

    companion object {
        const val K_SNAPSHOT = "cloud.config_snapshot"
        const val K_PASSPHRASE = "cloud.passphrase"
        const val MIN_PASSPHRASE = 6
        private const val CHECK_MARKER = "alerte-residents"
        private const val RESIDENT = "r:"
        private const val ACCOUNT = "a:"
        private const val ZONE = "zone"
    }
}
