package fr.alerteresidents.cloud

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.ResidentTracking
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.data.store.AccountStore
import fr.alerteresidents.data.store.AlertStore
import fr.alerteresidents.data.store.ConfigStore
import fr.alerteresidents.data.store.ResidentStore
import fr.alerteresidents.data.store.ZoneStore
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.util.DateParsing
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CloudSyncTest {

    // ------------------------------------------------------------------ faux appareil

    private class MemStore : ResidentStore, ZoneStore, AlertStore, AccountStore, ConfigStore {
        val residents = mutableMapOf<Long, Resident>()
        val alerts = mutableListOf<AlertEvent>()
        val accountMap = mutableMapOf<Long, WeenectAccount>()
        var zoneValue = FacilityZone()
        override suspend fun getResidentById(id: Long) = residents[id]
        override suspend fun getAllResidentsOnce() = residents.values.toList()
        override suspend fun updateTracking(tracking: ResidentTracking) {
            residents[tracking.id] = residents.getValue(tracking.id).let { r ->
                r.copy(
                    isInZone = tracking.isInZone, alertState = tracking.alertState, alertHandledBy = tracking.alertHandledBy,
                    alertHandledAt = tracking.alertHandledAt, pausedUntil = tracking.pausedUntil, pauseReason = tracking.pauseReason,
                    exitedAt = tracking.exitedAt
                )
            }
        }
        override suspend fun attachAccount(id: Long, accountId: Long) {}
        override suspend fun detachAccount(accountId: Long) {}
        override suspend fun getFacilityZoneOnce() = zoneValue
        override suspend fun insertAlert(alert: AlertEvent): Long { alerts += alert; return alerts.size.toLong() }
        override suspend fun acknowledgeForResident(residentId: Long, type: String, staffName: String, at: Long) {}
        override suspend fun getById(id: Long): WeenectAccount? = accountMap[id]
        override suspend fun findByUsername(username: String): WeenectAccount? = accountMap.values.find { it.username == username }
        override suspend fun insert(account: WeenectAccount) = insertAccount(account)
        override suspend fun update(account: WeenectAccount) = updateAccount(account)
        override suspend fun delete(account: WeenectAccount) { accountMap.remove(account.id) }

        // ConfigStore
        override suspend fun residents() = residents.values.sortedBy { it.id }
        override suspend fun insertResident(resident: Resident): Long {
            val id = (residents.keys.maxOrNull() ?: 0L) + 1
            residents[id] = resident.copy(id = id); return id
        }
        override suspend fun updateProfile(profile: ResidentProfile) {
            residents[profile.id] = residents.getValue(profile.id).copy(
                name = profile.name, roomNumber = profile.roomNumber, photoUri = profile.photoUri, avatarColorHex = profile.avatarColorHex,
                accountId = profile.accountId, trackerId = profile.trackerId, trackerName = profile.trackerName,
                emergencyContact = profile.emergencyContact, notes = profile.notes, isTrackingActive = profile.isTrackingActive,
                unit = profile.unit, riskLevel = profile.riskLevel
            )
        }
        override suspend fun setResidentSyncId(id: Long, syncId: String) { residents[id] = residents.getValue(id).copy(syncId = syncId) }
        override suspend fun removeResident(id: Long) { residents.remove(id) }
        override suspend fun accounts() = accountMap.values.sortedBy { it.id }
        override suspend fun insertAccount(account: WeenectAccount): Long {
            val id = (accountMap.keys.maxOrNull() ?: 0L) + 1
            accountMap[id] = account.copy(id = id); return id
        }
        override suspend fun updateAccount(account: WeenectAccount) { accountMap[account.id] = account }
        override suspend fun removeAccount(account: WeenectAccount) {
            accountMap.remove(account.id)
            residents.replaceAll { _, r -> if (r.accountId == account.id) r.copy(accountId = null) else r }
        }
        override suspend fun zone() = zoneValue
        override suspend fun saveZone(zone: FacilityZone) { zoneValue = zone }
    }

    private class MemPrefs : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private object PlainCipher : CredentialCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    private class FakeAlarms : CloudAlarmPort {
        val ringing = mutableMapOf<Long, Long>()
        val rings = mutableListOf<Pair<Long, String?>>()
        val infos = mutableListOf<String>()
        override fun alarmStartedAt(residentId: Long) = ringing[residentId]
        override fun ring(resident: Resident, zone: FacilityZone, isDrill: Boolean, reportedBy: String?) {
            ringing[resident.id] = System.currentTimeMillis(); rings += resident.id to reportedBy
        }
        override fun stop(residentId: Long) { ringing.remove(residentId) }
        override fun info(title: String, message: String) { infos += title }
    }

    // ------------------------------------------------------------------ faux Supabase

    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var staff = true
    @Volatile private var syncBody = state()
    @Volatile private var reportBody = """{"id":"inc-1","tracker_id":42,"status":"active"}"""
    @Volatile private var rpcStatus = 200
    @Volatile private var accessExpiresIn = 3600
    @Volatile private var configBody = config()
    @Volatile private var passphraseCheck: String? = null

    private val store = MemStore()
    private val prefs = MemPrefs()
    private val alarms = FakeAlarms()
    private lateinit var repo: WeenectRepository
    private lateinit var sync: CloudSync

    private fun state(incidents: String = "[]", pauses: String = "[]") =
        """{"now":"${DateParsing.formatIso(System.currentTimeMillis())}","incidents":$incidents,"pauses":$pauses,
           "devices":[{"id":"d1","name":"PC infirmerie","platform":"windows","monitoring_ok":true,"residents_count":3,
           "last_seen_at":"${DateParsing.formatIso(System.currentTimeMillis())}"}],"config":null}"""

    private fun config(residents: String = "[]", accounts: String = "[]", zone: String = "null", zoneAt: String = "null") =
        """{"now":"${DateParsing.formatIso(System.currentTimeMillis())}","residents":$residents,"accounts":$accounts,
           "zone":$zone,"zone_updated_at":$zoneAt,"passphrase_check":null}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                calls += path to body
                return when {
                    path.startsWith("/auth/v1/token") ->
                        if (body.contains("mauvais")) MockResponse().setResponseCode(400).setBody("""{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
                        else MockResponse().setBody("""{"access_token":"A${calls.size}","refresh_token":"R${calls.size}","expires_in":$accessExpiresIn,"user":{"email":"poste@ehpad.fr"}}""")
                    path.endsWith("/rpc/check_access") -> MockResponse().setBody("""{"staff":$staff,"display_name":"Infirmerie"}""")
                    rpcStatus != 200 -> MockResponse().setResponseCode(rpcStatus).setBody("""{"code":"PGRST301","message":"JWT expired"}""")
                    path.endsWith("/rpc/sync_state") -> MockResponse().setBody(syncBody)
                    path.endsWith("/rpc/sync_config") -> MockResponse().setBody(configBody)
                    path.endsWith("/rpc/init_passphrase_check") -> {
                        val proposed = Regex("\"p_check\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                        if (passphraseCheck == null) passphraseCheck = proposed
                        MockResponse().setBody("\"$passphraseCheck\"")
                    }
                    path.endsWith("/rpc/save_zone") || path.endsWith("/rpc/save_resident") || path.endsWith("/rpc/save_account") ->
                        MockResponse().setBody("\"2026-10-03T10:00:00+00:00\"")
                    path.endsWith("/rpc/report_exit") -> MockResponse().setBody(reportBody)
                    path.contains("/rpc/") -> MockResponse().setBody("""{"id":"x","tracker_id":42,"status":"resolved"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repo = WeenectRepository(store, store, store, store, PlainCipher, baseUrl = server.url("/weenect/").toString())
        sync = CloudSync(
            prefs, PlainCipher, store, store, store, repo, alarms, "windows", "2.0", "PC test",
            staffName = { "Sophie" }, monitoringOk = { true }
        )
        repo.sharedHooks = sync
        store.residents[1] = Resident(id = 1, name = "Jeanne", trackerId = 42, accountId = 1, isInZone = true,
            lastUpdatedTime = System.currentTimeMillis() - 10 * 60_000L)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun connect() = runBlocking {
        sync.connect(server.url("/").toString(), "sb_publishable_x", "poste@ehpad.fr", "secret", "PC test")
    }

    private fun rpcCalls(name: String) = calls.filter { it.first.endsWith("/rpc/$name") }

    // ------------------------------------------------------------------ tests

    @Test
    fun `connexion - enregistre les reglages et chiffre le jeton`() {
        val r = connect()
        assertTrue(r.isSuccess)
        assertEquals("Infirmerie", r.getOrNull())
        assertTrue(sync.isConfigured)
        assertEquals("enc:R1", prefs.get(CloudSync.K_REFRESH))
        assertTrue(calls.first().first.contains("grant_type=password"))
    }

    @Test
    fun `connexion - mauvais mot de passe ou compte non autorise`() = runBlocking {
        val bad = sync.connect(server.url("/").toString(), "k", "poste@ehpad.fr", "mauvais", "PC")
        assertEquals("E-mail ou mot de passe incorrect", bad.exceptionOrNull()?.message)
        staff = false
        val notStaff = connect()
        assertTrue(notStaff.exceptionOrNull()!!.message!!.contains("add_staff"))
        assertFalse(sync.isConfigured)
    }

    @Test
    fun `adresse du projet - formats acceptes`() {
        assertEquals("https://abcdefghijklmnopqrst.supabase.co", SupabaseClient.normalizeUrl("abcdefghijklmnopqrst"))
        assertEquals("https://x.supabase.co", SupabaseClient.normalizeUrl(" x.supabase.co/rest/v1/ "))
        assertEquals("https://db.mon-ehpad.fr", SupabaseClient.normalizeUrl("https://db.mon-ehpad.fr/"))
        assertNull(SupabaseClient.normalizeUrl("   "))
        assertNull(SupabaseClient.normalizeUrl("pas une adresse"))
    }

    @Test
    fun `sortie detectee ici - envoyee puis etat partage lu`() = runBlocking {
        connect()
        sync.exitConfirmed(store.residents[1]!!.copy(lastLatitude = 47.1, lastLongitude = -1.6, distanceFromCenterMeters = 250.0), isDrill = false)
        assertTrue(sync.syncOnce())
        val report = rpcCalls("report_exit").single().second
        assertTrue(report.contains("\"p_tracker_id\":42"))
        assertTrue(report.contains("\"p_is_drill\":false"))
        assertTrue(report.contains("\"p_device_name\":\"PC test\""))
        assertEquals(1, rpcCalls("sync_state").size)
        assertEquals(1, sync.state.value.devices.size)
        assertTrue(sync.state.value.connected)
    }

    @Test
    fun `prise en charge sur un autre appareil - alarme coupee et etat local mis a jour`() = runBlocking {
        connect()
        store.residents[1] = store.residents[1]!!.copy(isInZone = false, alertState = AlertState.ACTIVE, exitedAt = System.currentTimeMillis() - 60_000)
        alarms.ringing[1] = System.currentTimeMillis() - 50_000
        val now = DateParsing.formatIso(System.currentTimeMillis())
        syncBody = state("""[{"id":"inc-1","tracker_id":42,"status":"handling","handled_by":"Marc","handled_at":"$now"}]""")
        sync.syncOnce()
        assertNull(alarms.alarmStartedAt(1))
        assertEquals(AlertState.HANDLING, store.residents[1]!!.alertState)
        assertEquals("Marc", store.residents[1]!!.alertHandledBy)
        assertEquals(1, alarms.infos.size)
        // Relu au cycle suivant : rien de plus
        sync.syncOnce()
        assertEquals(1, alarms.infos.size)
    }

    @Test
    fun `sortie detectee ailleurs - sonne une seule fois meme si on coupe le son`() = runBlocking {
        connect()
        val opened = DateParsing.formatIso(System.currentTimeMillis() - 5_000)
        syncBody = state("""[{"id":"inc-2","tracker_id":42,"status":"active","opened_at":"$opened","opened_by_device_name":"Téléphone Sophie"}]""")
        sync.syncOnce()
        assertEquals(listOf(1L to "Téléphone Sophie"), alarms.rings)
        alarms.stop(1) // un soignant coupe le son ici
        sync.syncOnce()
        assertEquals(1, alarms.rings.size)
    }

    @Test
    fun `sortie ailleurs mais position plus recente dans la zone ici - retour confirme sans sonner`() = runBlocking {
        connect()
        val opened = System.currentTimeMillis() - 5 * 60_000L
        store.residents[1] = store.residents[1]!!.copy(lastUpdatedTime = opened + 2 * 60_000L)
        syncBody = state("""[{"id":"inc-3","tracker_id":42,"status":"active","opened_at":"${DateParsing.formatIso(opened)}"}]""")
        sync.syncOnce()
        assertTrue(alarms.rings.isEmpty())
        sync.syncOnce()
        val resolve = rpcCalls("resolve_incident").single().second
        assertTrue(resolve.contains("\"p_resolution\":\"returned\""))
    }

    @Test
    fun `action locale - je m en occupe et sortie accompagnee partagees dans l ordre`() = runBlocking {
        connect()
        store.residents[1] = store.residents[1]!!.copy(isInZone = false, alertState = AlertState.ACTIVE)
        repo.markHandling(1, "Sophie")
        repo.startOuting(1, System.currentTimeMillis() + 3_600_000, "Promenade", "Sophie")
        sync.syncOnce()
        val order = calls.map { it.first.substringAfterLast('/') }.filter { it in setOf("handle_incident", "set_pause", "sync_state") }
        assertEquals(listOf("handle_incident", "set_pause", "sync_state"), order)
        assertTrue(rpcCalls("handle_incident").single().second.contains("\"p_staff\":\"Sophie\""))
        assertTrue(rpcCalls("set_pause").single().second.contains("\"p_reason\":\"Promenade\""))
    }

    @Test
    fun `sortie accompagnee declaree ailleurs - surveillance suspendue ici`() = runBlocking {
        connect()
        val until = System.currentTimeMillis() + 3_600_000
        syncBody = state(pauses = """[{"tracker_id":42,"paused_until":"${DateParsing.formatIso(until)}","reason":"Médecin","set_by":"Marc","updated_at":"x1"}]""")
        sync.syncOnce()
        val r = store.residents[1]!!
        assertTrue(r.isPaused())
        assertEquals("Médecin", r.pauseReason)
        // Fin de sortie ailleurs
        syncBody = state(pauses = """[{"tracker_id":42,"paused_until":null,"set_by":"Marc","updated_at":"x2"}]""")
        sync.syncOnce()
        assertFalse(store.residents[1]!!.isPaused())
    }

    @Test
    fun `hors ligne - les actions attendent et partent au retour du reseau`() = runBlocking {
        connect()
        store.residents[1] = store.residents[1]!!.copy(isInZone = false, alertState = AlertState.ACTIVE)
        repo.markFound(1, "Sophie")
        rpcStatus = 503
        assertFalse(sync.syncOnce())
        assertEquals(1, sync.state.value.pendingChanges)
        assertFalse(sync.state.value.connected)
        rpcStatus = 200
        assertTrue(sync.syncOnce())
        // 1 tentative refusée (503) puis 1 envoi réussi
        assertEquals(2, rpcCalls("resolve_incident").count { it.second.contains("\"p_resolution\":\"found\"") })
        assertEquals(0, sync.state.value.pendingChanges)
    }

    @Test
    fun `session expiree - renouvelee avec le jeton enregistre`() = runBlocking {
        accessExpiresIn = 30 // expire dans moins d'une minute : renouvellement avant l'appel
        connect()
        sync.syncOnce()
        assertTrue(calls.any { it.first.contains("grant_type=refresh_token") && it.second.contains("R1") })
        assertTrue(prefs.get(CloudSync.K_REFRESH)!!.startsWith("enc:R"))
        assertTrue(sync.state.value.connected)
    }

    @Test
    fun `exercice declenche ailleurs - sonne ici en mode exercice`() = runBlocking {
        connect()
        syncBody = state("""[{"id":"inc-d","tracker_id":42,"is_drill":true,"status":"active","opened_by_device_name":"PC cadre"}]""")
        sync.syncOnce()
        assertEquals(1, alarms.rings.size)
        // « Je m'en occupe » sur l'alarme d'exercice : partagé sur l'incident d'exercice
        repo.markHandling(1, "Sophie")
        sync.syncOnce()
        assertTrue(rpcCalls("handle_incident").single().second.contains("\"p_is_drill\":true"))
    }

    @Test
    fun `deconnexion - oublie les reglages et le jeton`() = runBlocking {
        connect()
        sync.disconnect()
        assertFalse(sync.isConfigured)
        assertNull(prefs.get(CloudSync.K_REFRESH))
        assertTrue(sync.syncOnce())
        assertFalse(sync.state.value.configured)
    }
    // ------------------------------------------------------------------ synchronisation des fiches

    private fun remoteResident(id: String, name: String, tracker: Long?, extra: String = "") =
        """{"id":"$id","name":"$name","room_number":"8","unit":"Unité B","tracker_id":${tracker ?: "null"},"risk_level":1$extra}"""

    @Test
    fun `fiche ajoutee sur un autre appareil - creee ici avec sa zone`() = runBlocking {
        connect()
        store.residents.clear()
        configBody = config(
            residents = "[${remoteResident("r-1", "Marcel Dupont", 77)}]",
            zone = """{"name":"MAS Les Tilleuls","centerLatitude":45.7,"centerLongitude":4.8,"radiusMeters":200.0}""",
            zoneAt = "\"2026-10-03T09:00:00+00:00\""
        )
        assertTrue(sync.syncOnce())
        val r = store.residents.values.single()
        assertEquals("Marcel Dupont", r.name)
        assertEquals("Unité B", r.unit)
        assertEquals(77L, r.trackerId)
        assertEquals("r-1", r.syncId)
        assertEquals("MAS Les Tilleuls", store.zoneValue.name)
        assertEquals(200.0, store.zoneValue.radiusMeters, 0.0)
        // Rien n'est renvoyé : ce sont des fiches reçues, pas des modifications locales
        assertTrue(rpcCalls("save_resident").isEmpty())
        assertTrue(rpcCalls("save_zone").isEmpty())
        assertEquals(1, sync.state.value.sharedResidents)
    }

    @Test
    fun `fiche ajoutee ici - envoyee une seule fois puis a chaque modification`() = runBlocking {
        connect()
        sync.syncOnce()
        val pushed = rpcCalls("save_resident").single().second
        assertTrue(pushed.contains("\"name\":\"Jeanne\""))
        assertTrue(pushed.contains("\"tracker_id\":42"))
        val syncId = store.residents[1]!!.syncId!!
        assertTrue(pushed.contains(syncId))
        assertEquals(1, rpcCalls("save_zone").size) // premier appareil : la zone est partagée aussi

        sync.syncOnce()
        assertEquals(1, rpcCalls("save_resident").size) // inchangée : pas de renvoi

        store.residents[1] = store.residents[1]!!.copy(roomNumber = "14")
        sync.syncOnce()
        assertEquals(2, rpcCalls("save_resident").size)
        assertTrue(rpcCalls("save_resident").last().second.contains("\"room_number\":\"14\""))
    }

    @Test
    fun `premier raccordement - la fiche locale est rattachee a la fiche partagee de meme balise`() = runBlocking {
        connect()
        configBody = config(residents = "[${remoteResident("r-42", "Jeanne Martin", 42)}]")
        sync.syncOnce()
        assertEquals(1, store.residents.size) // pas de doublon
        val r = store.residents[1]!!
        assertEquals("r-42", r.syncId)
        assertEquals("Jeanne Martin", r.name) // la fiche partagée fait foi
        assertTrue(rpcCalls("save_resident").isEmpty())
    }

    @Test
    fun `fiche retiree ailleurs - retiree ici, et inversement`() = runBlocking {
        connect()
        configBody = config(residents = "[${remoteResident("r-42", "Jeanne", 42)}, ${remoteResident("r-9", "Paul", 9)}]")
        sync.syncOnce()
        assertEquals(2, store.residents.size)

        // Retiré sur un autre appareil
        configBody = config(residents = "[${remoteResident("r-9", "Paul", 9, ",\"removed\":true")}]")
        sync.syncOnce()
        assertEquals(listOf("Jeanne"), store.residents.values.map { it.name })

        // Retiré ici
        store.residents.clear()
        configBody = config()
        sync.syncOnce()
        val removal = rpcCalls("save_resident").last().second
        assertTrue(removal.contains("r-42"))
        assertTrue(removal.contains("\"removed\":true"))
    }

    @Test
    fun `modification locale pas encore envoyee - n est pas ecrasee par la fiche partagee`() = runBlocking {
        connect()
        configBody = config(residents = "[${remoteResident("r-42", "Jeanne", 42)}]")
        sync.syncOnce()
        store.residents[1] = store.residents[1]!!.copy(notes = "Aime marcher le soir")
        configBody = config(residents = "[${remoteResident("r-42", "Jeanne", 42, ",\"notes\":\"\"")}]")
        rpcStatus = 503 // l'envoi échoue : la modification locale reste en attente
        sync.syncOnce()
        rpcStatus = 200
        sync.syncOnce()
        assertEquals("Aime marcher le soir", store.residents[1]!!.notes)
        assertTrue(rpcCalls("save_resident").last().second.contains("Aime marcher le soir"))
    }

    @Test
    fun `phrase secrete - mots de passe Weenect chiffres a l envoi et dechiffres a la reception`() = runBlocking {
        connect()
        store.accountMap[1] = WeenectAccount(id = 1, label = "Etab", username = "contact@mas.fr", encryptedPassword = "enc:motdepasse")
        assertTrue(sync.setPassphrase("phrase secrète").isSuccess)
        sync.syncOnce()
        val sent = rpcCalls("save_account").single().second
        assertFalse(sent.contains("motdepasse"))
        val enc = Regex("\"password_enc\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
        assertEquals("motdepasse", fr.alerteresidents.security.PassphraseCrypto.decrypt(enc, "phrase secrète"))

        // Un autre appareil reçoit le compte : mot de passe déchiffré puis rechiffré localement
        val other = fr.alerteresidents.security.PassphraseCrypto.encrypt("autre-mdp", "phrase secrète")
        configBody = config(accounts = """[{"id":"a-2","label":"Annexe","username":"annexe@mas.fr","password_enc":"$other"}]""")
        sync.syncOnce()
        val received = store.accountMap.values.single { it.syncId == "a-2" }
        assertEquals("enc:autre-mdp", received.encryptedPassword)

        // Mauvaise phrase sur un autre poste : refusée
        assertTrue(sync.setPassphrase("pas la bonne").isFailure)
    }

    @Test
    fun `sans phrase secrete - compte partage sans mot de passe et signale`() = runBlocking {
        connect()
        val enc = fr.alerteresidents.security.PassphraseCrypto.encrypt("mdp", "phrase secrète")
        configBody = config(accounts = """[{"id":"a-1","label":"Etab","username":"contact@mas.fr","password_enc":"$enc"}]""")
        sync.syncOnce()
        assertEquals("", store.accountMap.values.single().encryptedPassword)
        assertTrue(sync.state.value.passphraseNeeded)
    }

    @Test
    fun `sortie d un resident inconnu ici - sonne quand meme et se prend en charge`() = runBlocking {
        connect()
        syncBody = state("""[{"id":"inc-x","tracker_id":555,"resident_name":"Marcel","status":"active","opened_by_device_name":"Unité A"}]""")
        sync.syncOnce()
        val ghostId = CloudSync.FOREIGN_BASE - 555
        assertEquals(listOf(ghostId to "Unité A"), alarms.rings)
        assertTrue(sync.handleForeign(ghostId, "Sophie"))
        sync.syncOnce()
        assertTrue(rpcCalls("handle_incident").single().second.contains("\"p_tracker_id\":555"))
        // Pris en charge ailleurs : l'alarme fantôme s'arrête
        alarms.ring(Resident(id = ghostId, name = "Marcel"), FacilityZone(), false, null)
        syncBody = state("""[{"id":"inc-x","tracker_id":555,"resident_name":"Marcel","status":"handling","handled_by":"Marc"}]""")
        sync.syncOnce()
        assertNull(alarms.alarmStartedAt(ghostId))
    }
}
