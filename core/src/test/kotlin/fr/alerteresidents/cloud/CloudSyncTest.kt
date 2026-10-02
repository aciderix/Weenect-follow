package fr.alerteresidents.cloud

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentTracking
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.data.store.AccountStore
import fr.alerteresidents.data.store.AlertStore
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

    private class MemStore : ResidentStore, ZoneStore, AlertStore, AccountStore {
        val residents = mutableMapOf<Long, Resident>()
        val alerts = mutableListOf<AlertEvent>()
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
        override suspend fun getFacilityZoneOnce() = FacilityZone()
        override suspend fun insertAlert(alert: AlertEvent): Long { alerts += alert; return alerts.size.toLong() }
        override suspend fun acknowledgeForResident(residentId: Long, type: String, staffName: String, at: Long) {}
        override suspend fun getById(id: Long): WeenectAccount? = null
        override suspend fun findByUsername(username: String): WeenectAccount? = null
        override suspend fun insert(account: WeenectAccount) = 1L
        override suspend fun update(account: WeenectAccount) {}
        override suspend fun delete(account: WeenectAccount) {}
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

    private val store = MemStore()
    private val prefs = MemPrefs()
    private val alarms = FakeAlarms()
    private lateinit var repo: WeenectRepository
    private lateinit var sync: CloudSync

    private fun state(incidents: String = "[]", pauses: String = "[]") =
        """{"now":"${DateParsing.formatIso(System.currentTimeMillis())}","incidents":$incidents,"pauses":$pauses,
           "devices":[{"id":"d1","name":"PC infirmerie","platform":"windows","monitoring_ok":true,"residents_count":3,
           "last_seen_at":"${DateParsing.formatIso(System.currentTimeMillis())}"}],"config":null}"""

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
                    path.endsWith("/rpc/report_exit") -> MockResponse().setBody(reportBody)
                    path.contains("/rpc/") -> MockResponse().setBody("""{"id":"x","tracker_id":42,"status":"resolved"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        repo = WeenectRepository(store, store, store, store, PlainCipher, baseUrl = server.url("/weenect/").toString())
        sync = CloudSync(
            prefs, PlainCipher, store, store, repo, alarms, "windows", "2.0", "PC test",
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
}
