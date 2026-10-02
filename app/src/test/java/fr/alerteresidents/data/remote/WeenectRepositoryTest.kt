package fr.alerteresidents.data.remote

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.alerteresidents.data.local.AppDatabase
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.util.DateParsing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Chiffrement factice pour les tests (l'Android Keystore n'existe pas sous Robolectric). */
class FakeCipher : CredentialCipher {
    override fun encrypt(plain: String) = "enc:$plain"
    override fun decrypt(encoded: String) = encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
}

/**
 * Tests de bout en bout du moteur de surveillance : vraie base Room (en mémoire) + faux serveur Weenect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeenectRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: WeenectRepository

    private val exits = CopyOnWriteArrayList<Pair<Long, Boolean>>() // id, drill
    private val returns = CopyOnWriteArrayList<Long>()
    private val reminders = CopyOnWriteArrayList<Long>()
    private val warnings = CopyOnWriteArrayList<String>()
    private val outingsEnded = CopyOnWriteArrayList<Long>()
    @Volatile private var ringing = false

    @Volatile private var loginCode = 200
    @Volatile private var commandCode = 200
    private val positionResponses = ConcurrentLinkedQueue<MockResponse>()
    @Volatile private var now = 1_790_000_000_000L // 2026-09-21 environ

    private var accountId = 0L
    private val zone = FacilityZone(centerLatitude = CENTER_LAT, centerLongitude = CENTER_LON, radiusMeters = 150.0, isZoneActive = true)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/user/login") ->
                        if (loginCode == 200) json("""{"access_token":"tok","expires_in":3600,"refresh_token":"r"}""")
                        else MockResponse().setResponseCode(loginCode).setBody("""{"error":"bad credentials"}""")
                    path.contains("/position/refresh") || path.endsWith("/ring") || path.endsWith("/vibrate") || path.endsWith("/st-mode") ->
                        MockResponse().setResponseCode(commandCode)
                    path.endsWith("/mytracker") -> json("""{"items":[{"id":42,"name":"Balise Jean"},{"id":43,"name":"Balise Marie"}]}""")
                    path.contains("/position") -> positionResponses.poll() ?: json("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { db.facilityZoneDao().insertOrUpdate(zone) }

        repo = WeenectRepository(
            residentDao = db.residentDao(),
            facilityZoneDao = db.facilityZoneDao(),
            alertEventDao = db.alertEventDao(),
            accountDao = db.weenectAccountDao(),
            cipher = FakeCipher(),
            listener = object : MonitoringListener {
                override fun onExitConfirmed(resident: Resident, zone: FacilityZone, isDrill: Boolean) { exits.add(resident.id to isDrill) }
                override fun onReminder(resident: Resident, zone: FacilityZone) { reminders.add(resident.id) }
                override fun onReturned(resident: Resident) { returns.add(resident.id) }
                override fun onWarning(resident: Resident, type: String, message: String) { warnings.add(type) }
                override fun onOutingEnded(resident: Resident) { outingsEnded.add(resident.id) }
                override fun isAlarmRinging(residentId: Long) = ringing
            },
            settings = { MonitoringSettings(staleMinutes = 15, reminderMinutes = 5) },
            baseUrl = server.url("/v4/").toString(),
            httpClient = OkHttpClient(),
            clock = { now }
        )
        accountId = runBlocking { repo.saveAccount("Compte MAS", "mas@test.fr", "secret") }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    fun `position inside zone keeps resident safe and raises no alert`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON)

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isSuccess)
        val stored = stored(resident)
        assertTrue(stored.isInZone)
        assertEquals(80, stored.lastBattery)
        assertEquals(now, stored.lastSyncTime)
        assertNull(stored.lastSyncError)
        assertTrue(db.alertEventDao().getAllAlerts().first().isEmpty())
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `clear exit records an EXIT_ZONE alert and fires the alarm once`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        val stored = stored(resident)
        assertFalse(stored.isInZone)
        assertEquals(AlertState.ACTIVE, stored.alertState)
        assertEquals(now, stored.exitedAt)
        assertTrue(stored.distanceFromCenterMeters in 500.0..600.0)
        val alerts = db.alertEventDao().getAllAlerts().first()
        assertEquals(listOf(AlertType.EXIT_ZONE), alerts.map { it.alertType })
        assertEquals(listOf(resident.id to false), exits.toList())
    }

    @Test
    fun `resident already outside does not trigger a second alarm`() = runBlocking {
        val resident = insertResident(isInZone = false, alertState = AlertState.ACTIVE, lastAlarmAt = now)
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        assertTrue(exits.isEmpty())
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `coming back inside records an acknowledged ENTER_ZONE and fires the return callback`() = runBlocking {
        val resident = insertResident(isInZone = false, alertState = AlertState.HANDLING)
        enqueuePosition(CENTER_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        val enter = db.alertEventDao().getAllAlerts().first().single()
        assertEquals(AlertType.ENTER_ZONE, enter.alertType)
        assertTrue("un retour est informatif", enter.isAcknowledged)
        assertEquals(listOf(resident.id), returns.toList())
        val stored = stored(resident)
        assertTrue(stored.isInZone)
        assertEquals(AlertState.NONE, stored.alertState)
        assertNull(stored.exitedAt)
    }

    // ------------------------------------------------------------------ fail-safe (C2)

    @Test
    fun `wrong Weenect password is reported as a failure and recorded on the resident`() = runBlocking {
        loginCode = 401
        val resident = insertResident()

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        assertEquals("Identifiants Weenect refusés", stored(resident).lastSyncError)
    }

    @Test
    fun `resident without tracker is not reported as safe`() = runBlocking {
        val resident = insertResident(trackerId = null)

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        assertEquals("Aucune balise associée", stored(resident).lastSyncError)
        assertNull(stored(resident).lastLatitude)
    }

    @Test
    fun `position without coordinates is never replaced by the zone center`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(json("""[{"id":"1","latitude":null,"longitude":null,"battery":50}]"""))

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        assertNull(stored(resident).lastLatitude)
    }

    @Test
    fun `server error keeps the last position and records the error`() = runBlocking {
        val resident = insertResident(lat = 47.0001, lon = CENTER_LON)
        positionResponses.add(MockResponse().setResponseCode(500))

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        val stored = stored(resident)
        assertEquals(47.0001, stored.lastLatitude!!, 1e-9)
        assertEquals("Erreur Weenect (code 500)", stored.lastSyncError)
    }

    @Test
    fun `tracker unreachable for longer than the stale delay raises a single SYNC_ERROR warning`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)

        now += 20 * 60_000L
        positionResponses.add(MockResponse().setResponseCode(503))
        repo.syncResidentPosition(stored(resident))
        positionResponses.add(MockResponse().setResponseCode(503))
        repo.syncResidentPosition(stored(resident))

        assertEquals(listOf(AlertType.SYNC_ERROR), warnings.toList())
    }

    // ------------------------------------------------------------------ fraîcheur (C3)

    @Test
    fun `last update time is the tracker fix time`() = runBlocking {
        val resident = insertResident()
        val fixTime = "2026-01-15T08:30:00+00:00"
        enqueuePosition(CENTER_LAT, CENTER_LON, dateTracker = fixTime)

        repo.syncResidentPosition(resident)

        assertEquals(DateParsing.parseIso(fixTime), stored(resident).lastUpdatedTime)
    }

    @Test
    fun `old fix raises one TRACKER_OFFLINE warning`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON, dateTracker = iso(now - 60 * 60_000L))
        enqueuePosition(CENTER_LAT, CENTER_LON, dateTracker = iso(now - 60 * 60_000L))

        repo.syncResidentPosition(resident)
        repo.syncResidentPosition(stored(resident))

        assertEquals(listOf(AlertType.TRACKER_OFFLINE), warnings.toList())
        assertTrue(stored(resident).offlineNotified)
    }

    // ------------------------------------------------------------------ hystérésis (M7)

    @Test
    fun `fix just outside the border within its accuracy waits for confirmation`() = runBlocking {
        val resident = insertResident()
        // ≈ 167 m du centre (rayon 150) avec une précision de 30 m → ambigu
        enqueuePosition(CENTER_LAT + 0.0015, CENTER_LON, radius = 30, id = "fix-1")

        repo.syncResidentPosition(resident)

        val pending = stored(resident)
        assertTrue("pas d'alarme sur un seul fix ambigu", pending.isInZone)
        assertEquals("fix-1", pending.pendingExitFixId)
        assertTrue(exits.isEmpty())
        assertTrue("un nouveau fix est demandé", recordedPaths().any { it.endsWith("/position/refresh") })

        // Second fix différent, toujours dehors → sortie confirmée
        enqueuePosition(CENTER_LAT + 0.0016, CENTER_LON, radius = 30, id = "fix-2")
        repo.syncResidentPosition(pending)
        assertFalse(stored(resident).isInZone)
        assertEquals(1, exits.size)
    }

    @Test
    fun `ambiguous fix that comes back inside cancels the pending exit`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT + 0.0015, CENTER_LON, radius = 30, id = "fix-1")
        repo.syncResidentPosition(resident)
        enqueuePosition(CENTER_LAT, CENTER_LON, id = "fix-2")
        repo.syncResidentPosition(stored(resident))

        assertTrue(stored(resident).isInZone)
        assertNull(stored(resident).pendingExitFixId)
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `same ambiguous fix is confirmed after one minute without a new fix`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT + 0.0015, CENTER_LON, radius = 30, id = "fix-1")
        repo.syncResidentPosition(resident)
        now += 61_000L
        enqueuePosition(CENTER_LAT + 0.0015, CENTER_LON, radius = 30, id = "fix-1")
        repo.syncResidentPosition(stored(resident))

        assertEquals(1, exits.size)
    }

    // ------------------------------------------------------------------ rappels et prise en charge (M6, 24)

    @Test
    fun `alarm is replayed after the reminder delay while nobody handles the alert`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)

        now += 6 * 60_000L
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(stored(resident))

        assertEquals(listOf(resident.id), reminders.toList())
    }

    @Test
    fun `no reminder while the alarm is still ringing`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)
        ringing = true
        now += 6 * 60_000L
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(stored(resident))

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `handling an alert acknowledges it, stops reminders and keeps the real position`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)

        repo.markHandling(resident.id, "Infirmière Paula")

        val handled = stored(resident)
        assertEquals(AlertState.HANDLING, handled.alertState)
        assertEquals("Infirmière Paula", handled.alertHandledBy)
        assertEquals(OUTSIDE_LAT, handled.lastLatitude!!, 1e-9) // position réelle conservée (C6)
        assertFalse(handled.isInZone)
        assertTrue(db.alertEventDao().getUnacknowledgedAlerts().first().isEmpty())
        val exit = db.alertEventDao().getAllAlertsOnce().first { it.alertType == AlertType.EXIT_ZONE }
        assertEquals("Infirmière Paula", exit.acknowledgedBy)
        assertEquals(now, exit.acknowledgedAt)

        now += 10 * 60_000L
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(handled)
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `marking a resident found keeps the position until the tracker confirms the return`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)

        repo.markFound(resident.id, "Paul")

        val found = stored(resident)
        assertEquals(AlertState.RESOLVED, found.alertState)
        assertFalse(found.isInZone)
        assertEquals(OUTSIDE_LAT, found.lastLatitude!!, 1e-9)
    }

    // ------------------------------------------------------------------ sorties accompagnées (29)

    @Test
    fun `outing suspends zone alerts then resumes automatically`() = runBlocking {
        val resident = insertResident()
        repo.startOuting(resident.id, now + 60 * 60_000L, "Sortie famille", "Paul")

        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(stored(resident))
        assertTrue("aucune alarme pendant la sortie", exits.isEmpty())
        assertEquals(OUTSIDE_LAT, stored(resident).lastLatitude!!, 1e-9)

        // Fin de sortie : toujours dehors → alarme
        now += 61 * 60_000L
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(stored(resident))
        assertEquals(listOf(resident.id), outingsEnded.toList())
        assertEquals(1, exits.size)
        assertNull(stored(resident).pausedUntil)
        val types = db.alertEventDao().getAllAlertsOnce().map { it.alertType }
        assertTrue(types.containsAll(listOf(AlertType.OUTING_START, AlertType.OUTING_END, AlertType.EXIT_ZONE)))
    }

    // ------------------------------------------------------------------ batterie (M6)

    @Test
    fun `low battery is reported once and re-armed when the battery recovers`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON, battery = 15)
        enqueuePosition(CENTER_LAT, CENTER_LON, battery = 14)
        repo.syncResidentPosition(resident)
        repo.syncResidentPosition(stored(resident))
        assertEquals(listOf(AlertType.LOW_BATTERY), warnings.toList())

        enqueuePosition(CENTER_LAT, CENTER_LON, battery = 90)
        repo.syncResidentPosition(stored(resident))
        assertFalse(stored(resident).lowBatteryNotified)
    }

    // ------------------------------------------------------------------ concurrence (M2)

    @Test
    fun `concurrent syncs of the same resident raise a single alarm`() = runBlocking {
        val resident = insertResident()
        repeat(4) { enqueuePosition(OUTSIDE_LAT, CENTER_LON) }

        (1..4).map { async { repo.syncResidentPosition(resident) } }.awaitAll()

        assertEquals(1, exits.size)
        assertEquals(1, db.alertEventDao().getAllAlertsOnce().count { it.alertType == AlertType.EXIT_ZONE })
    }

    @Test
    fun `a profile edit is not overwritten by a sync`() = runBlocking {
        val resident = insertResident()
        db.residentDao().updateProfile(profileOf(resident).copy(name = "Jean-Pierre Dupont", roomNumber = "Ch. 99"))
        enqueuePosition(CENTER_LAT, CENTER_LON)

        repo.syncResidentPosition(resident) // objet « ancien » passé volontairement

        val stored = stored(resident)
        assertEquals("Jean-Pierre Dupont", stored.name)
        assertEquals("Ch. 99", stored.roomNumber)
    }

    // ------------------------------------------------------------------ authentification

    @Test
    fun `expired token triggers a new login and the request is retried`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid token"}"""))
        enqueuePosition(CENTER_LAT, CENTER_LON)

        assertTrue(repo.syncResidentPosition(resident).isSuccess)

        val paths = recordedPaths()
        assertEquals(2, paths.count { it.endsWith("/user/login") })
    }

    @Test
    fun `stored password is encrypted and used with the JWT prefix`() = runBlocking {
        val account = db.weenectAccountDao().getById(accountId)!!
        assertEquals("enc:secret", account.encryptedPassword)

        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)

        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertTrue(requests.first { it.path!!.endsWith("/user/login") }.body.readUtf8().contains("\"password\":\"secret\""))
        requests.filter { it.path!!.contains("/position") }.forEach { assertEquals("JWT tok", it.getHeader("Authorization")) }
    }

    @Test
    fun `legacy plaintext credentials are migrated to an encrypted account`() = runBlocking {
        val legacyId = db.residentDao().insertResident(
            Resident(name = "Ancien", weenectUsername = "famille@test.fr", weenectPassword = "pwd", trackerId = TRACKER_ID)
        )

        assertEquals(1, repo.migrateLegacyCredentials())

        val migrated = db.residentDao().getResidentById(legacyId)!!
        assertEquals("", migrated.weenectPassword)
        assertEquals("", migrated.weenectUsername)
        val account = db.weenectAccountDao().getById(migrated.accountId!!)!!
        assertEquals("famille@test.fr", account.username)
        assertEquals("enc:pwd", account.encryptedPassword)
    }

    @Test
    fun `getTrackers lists the trackers of the account`() = runBlocking {
        val trackers = repo.getTrackersForAccount(accountId).getOrThrow()
        assertEquals(listOf(42L, 43L), trackers.map { it.id })
    }

    // ------------------------------------------------------------------ commandes (M1)

    @Test
    fun `ring command reports server errors`() = runBlocking {
        commandCode = 500
        assertTrue(repo.ringTracker(insertResident()).isFailure)
    }

    @Test
    fun `ring command succeeds and reaches the tracker endpoint`() = runBlocking {
        assertTrue(repo.ringTracker(insertResident()).isSuccess)
        assertTrue(recordedPaths().any { it.endsWith("/mytracker/$TRACKER_ID/ring") })
    }

    // ------------------------------------------------------------------ exercices (35)

    @Test
    fun `drill fires the alarm and logs a drill event without touching the resident`() = runBlocking {
        val resident = insertResident()

        repo.simulateExit(resident.id)

        assertEquals(listOf(resident.id to true), exits.toList())
        val event = db.alertEventDao().getAllAlertsOnce().single()
        assertTrue(event.isDrill)
        assertTrue("un exercice ne compte pas dans le badge", db.alertEventDao().getUnacknowledgedAlerts().first().isEmpty())
        assertTrue(stored(resident).isInZone)
    }

    // ------------------------------------------------------------------ zones annexes / nuit (45)

    @Test
    fun `annex zone allowed by day but not at night`() = runBlocking {
        val garden = fr.alerteresidents.data.model.ExtraZone("Jardin", OUTSIDE_LAT, CENTER_LON, 80.0, allowedAtNight = false)
        val hourNow = DateParsing.hourOf(now)
        db.facilityZoneDao().insertOrUpdate(
            zone.copy(
                extraZonesJson = FacilityZone.encodeExtraZones(listOf(garden)),
                nightModeEnabled = true,
                // La plage de nuit est choisie pour inclure ou exclure l'heure courante du test
                nightStartHour = (hourNow + 1) % 24, nightEndHour = (hourNow + 2) % 24
            )
        )
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(resident)
        assertTrue("jardin autorisé le jour", stored(resident).isInZone)

        db.facilityZoneDao().insertOrUpdate(db.facilityZoneDao().getFacilityZoneOnce()!!.copy(nightStartHour = hourNow, nightEndHour = (hourNow + 1) % 24))
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)
        repo.syncResidentPosition(stored(resident))
        assertFalse("jardin interdit la nuit", stored(resident).isInZone)
    }

    // ------------------------------------------------------------------ historique (47)

    @Test
    fun `history is sorted from oldest to newest and skips fixes without coordinates`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(
            json(
                """[{"id":"3","latitude":47.003,"longitude":-1.0,"date_tracker":"2026-10-02T10:30:00+00:00"},
                   {"id":"2","latitude":null,"longitude":null,"date_tracker":"2026-10-02T10:20:00+00:00"},
                   {"id":"1","latitude":47.001,"longitude":-1.0,"date_tracker":"2026-10-02T10:10:00+00:00"}]"""
            )
        )

        val points = repo.getHistory(resident, 2).getOrThrow()

        assertEquals(listOf(47.001, 47.003), points.map { it.latitude })
        val req = recordedPaths().first { it.contains("/position") }
        assertTrue("période demandée à l'API : $req", req.contains("start=") && req.contains("end="))
    }

    // ------------------------------------------------------------------

    private suspend fun insertResident(
        isInZone: Boolean = true,
        trackerId: Long? = TRACKER_ID,
        alertState: String = AlertState.NONE,
        lastAlarmAt: Long? = null,
        lat: Double? = null,
        lon: Double? = null
    ): Resident {
        val r = Resident(
            name = "Jean Dupont", roomNumber = "Ch. 12", accountId = accountId, trackerId = trackerId,
            isInZone = isInZone, alertState = alertState, lastAlarmAt = lastAlarmAt,
            exitedAt = if (isInZone) null else now, lastLatitude = lat, lastLongitude = lon
        )
        return r.copy(id = db.residentDao().insertResident(r))
    }

    private suspend fun stored(r: Resident) = db.residentDao().getResidentById(r.id)!!

    private fun profileOf(r: Resident) = ResidentProfile(
        r.id, r.name, r.roomNumber, r.photoUri, r.avatarColorHex, r.accountId, r.trackerId, r.trackerName,
        r.emergencyContact, r.notes, r.isTrackingActive, r.unit, r.riskLevel
    )

    private fun iso(t: Long) = DateParsing.formatIso(t)

    private fun enqueuePosition(
        lat: Double, lon: Double, dateTracker: String = iso(now - 30_000L),
        radius: Int? = null, id: String = "p${positionResponses.size}-${System.nanoTime()}", battery: Int = 80
    ) {
        positionResponses.add(
            json(
                """[{"id":"$id","latitude":$lat,"longitude":$lon,"battery":$battery,"speed":0.0,
                   ${radius?.let { "\"radius\":$it," } ?: ""}"type":"CMD-WBT-OUT","date_tracker":"$dateTracker"}]"""
            )
        )
    }

    private fun recordedPaths(): List<String> = (1..server.requestCount).map { server.takeRequest().path.orEmpty() }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TRACKER_ID = 42L
        const val CENTER_LAT = 47.0
        const val CENTER_LON = -1.0
        const val OUTSIDE_LAT = 47.005 // ≈ 556 m au nord
    }
}
