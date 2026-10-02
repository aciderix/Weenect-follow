package com.example.data.remote

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests de bout en bout du WeenectRepository : vraie base Room (en mémoire) + faux serveur Weenect.
 *
 * Les tests marqués @Ignore décrivent le comportement ATTENDU pour des défauts identifiés lors de
 * l'audit (voir AUDIT.md). Ils échouent avec le code actuel : retirer le @Ignore une fois corrigé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeenectRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: WeenectRepository

    private val exitEvents = CopyOnWriteArrayList<Pair<Long, Double>>()
    private val enterEvents = CopyOnWriteArrayList<Long>()

    // Réponses configurables du faux serveur
    @Volatile private var loginCode = 200
    @Volatile private var commandCode = 200
    private val positionResponses = java.util.concurrent.ConcurrentLinkedQueue<MockResponse>()

    private val zone = FacilityZone(
        centerLatitude = CENTER_LAT,
        centerLongitude = CENTER_LON,
        radiusMeters = 150.0,
        isZoneActive = true
    )

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
                    path.contains("/position/refresh") || path.endsWith("/ring") ||
                        path.endsWith("/vibrate") || path.endsWith("/st-mode") ->
                        MockResponse().setResponseCode(commandCode)
                    path.contains("/position") -> positionResponses.poll() ?: json("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { db.facilityZoneDao().insertOrUpdate(zone) }

        repo = WeenectRepository(
            residentDao = db.residentDao(),
            facilityZoneDao = db.facilityZoneDao(),
            alertEventDao = db.alertEventDao(),
            onZoneExitDetected = { r, d -> exitEvents.add(r.id to d) },
            onZoneEnterDetected = { r -> enterEvents.add(r.id) },
            baseUrl = server.url("/v4/").toString()
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Comportements actuels corrects
    // ---------------------------------------------------------------------------------------

    @Test
    fun `position inside zone keeps resident safe and raises no alert`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON)

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isSuccess)
        val stored = db.residentDao().getResidentById(resident.id)!!
        assertTrue(stored.isInZone)
        assertEquals(CENTER_LAT, stored.lastLatitude!!, 1e-9)
        assertEquals(80, stored.lastBattery)
        assertTrue(db.alertEventDao().getAllAlerts().first().isEmpty())
        assertTrue(exitEvents.isEmpty())
    }

    @Test
    fun `leaving the zone records an EXIT_ZONE alert and fires the alarm callback once`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        val stored = db.residentDao().getResidentById(resident.id)!!
        assertFalse(stored.isInZone)
        assertTrue("distance ≈ 556 m attendue, obtenue ${stored.distanceFromCenterMeters}",
            stored.distanceFromCenterMeters in 500.0..600.0)

        val alerts = db.alertEventDao().getAllAlerts().first()
        assertEquals(1, alerts.size)
        assertEquals("EXIT_ZONE", alerts[0].alertType)
        assertFalse(alerts[0].isAcknowledged)
        assertEquals(listOf(resident.id), exitEvents.map { it.first })
    }

    @Test
    fun `resident already outside does not trigger a second alarm`() = runBlocking {
        val resident = insertResident(isInZone = false)
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        assertTrue(db.alertEventDao().getAllAlerts().first().isEmpty())
        assertTrue(exitEvents.isEmpty())
    }

    @Test
    fun `coming back inside records ENTER_ZONE and fires the enter callback`() = runBlocking {
        val resident = insertResident(isInZone = false)
        enqueuePosition(CENTER_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        val alerts = db.alertEventDao().getAllAlerts().first()
        assertEquals(listOf("ENTER_ZONE"), alerts.map { it.alertType })
        assertEquals(listOf(resident.id), enterEvents.toList())
        assertTrue(db.residentDao().getResidentById(resident.id)!!.isInZone)
    }

    @Test
    fun `expired token triggers a new login and the request is retried`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid token"}"""))
        enqueuePosition(CENTER_LAT, CENTER_LON)

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isSuccess)
        val paths = recordedPaths()
        assertEquals(2, paths.count { it.endsWith("/user/login") })
        assertEquals(2, paths.count { it.contains("/mytracker/$TRACKER_ID/position") })
    }

    @Test
    fun `token is sent with the JWT prefix and reused between calls`() = runBlocking {
        val resident = insertResident()
        enqueuePosition(CENTER_LAT, CENTER_LON)
        enqueuePosition(CENTER_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)
        repo.syncResidentPosition(db.residentDao().getResidentById(resident.id)!!)

        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertEquals(1, requests.count { it.path!!.endsWith("/user/login") })
        requests.filter { it.path!!.contains("/position") }.forEach {
            assertEquals("JWT tok", it.getHeader("Authorization"))
        }
    }

    @Test
    fun `polygon zone uses polygon containment instead of the radius`() = runBlocking {
        // Rectangle étroit autour du centre : le point à ~110 m au nord est DANS le rayon de 150 m
        // mais HORS du polygone.
        val polygon = listOf(
            CENTER_LAT - 0.0005 to CENTER_LON - 0.0005,
            CENTER_LAT - 0.0005 to CENTER_LON + 0.0005,
            CENTER_LAT + 0.0005 to CENTER_LON + 0.0005,
            CENTER_LAT + 0.0005 to CENTER_LON - 0.0005
        )
        db.facilityZoneDao().insertOrUpdate(
            zone.copy(zoneType = "POLYGON", polygonPointsJson = FacilityZone.encodePolygonPoints(polygon))
        )
        val resident = insertResident()
        enqueuePosition(CENTER_LAT + 0.001, CENTER_LON)

        repo.syncResidentPosition(resident)

        val stored = db.residentDao().getResidentById(resident.id)!!
        assertFalse(stored.isInZone)
        assertTrue("distance au bord ≈ 55 m, obtenue ${stored.distanceFromCenterMeters}",
            stored.distanceFromCenterMeters in 45.0..65.0)
    }

    @Test
    fun `inactive zone never reports a resident out of zone`() = runBlocking {
        db.facilityZoneDao().insertOrUpdate(zone.copy(isZoneActive = false))
        val resident = insertResident()
        enqueuePosition(OUTSIDE_LAT, CENTER_LON)

        repo.syncResidentPosition(resident)

        assertTrue(db.residentDao().getResidentById(resident.id)!!.isInZone)
        assertTrue(exitEvents.isEmpty())
    }

    @Test
    fun `server error returns a failure and leaves the stored resident untouched`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(MockResponse().setResponseCode(500))

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        assertEquals(resident, db.residentDao().getResidentById(resident.id))
    }

    @Test
    fun `getTrackers returns the trackers of the account`() = runBlocking {
        server.dispatcher.let { original ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.endsWith("/mytracker"))
                        json("""{"items":[{"id":123456,"name":"Balise Jean"}]}""")
                    else original.dispatch(request)
            }
        }

        val trackers = repo.getTrackers("user@test.fr", "pwd").getOrThrow()

        assertEquals(1, trackers.size)
        assertEquals(123456L, trackers[0].id)
        assertEquals("Balise Jean", trackers[0].name)
    }

    // ---------------------------------------------------------------------------------------
    // Défauts identifiés par l'audit (comportement attendu) — échouent avec le code actuel
    // ---------------------------------------------------------------------------------------

    @Ignore("AUDIT C2 : un mauvais mot de passe Weenect est masqué, la synchro renvoie un succès avec l'ancienne position")
    @Test
    fun `wrong Weenect password is reported as a failure`() = runBlocking {
        loginCode = 401
        val resident = insertResident()

        val result = repo.syncResidentPosition(resident)

        assertTrue("la synchro doit échouer si la connexion Weenect échoue", result.isFailure)
    }

    @Ignore("AUDIT C2 : un résident sans identifiants/balise est placé au centre de la zone et affiché « en sécurité »")
    @Test
    fun `resident without tracker credentials is not reported as safe`() = runBlocking {
        val resident = insertResident(username = "", password = "", trackerId = null)

        val result = repo.syncResidentPosition(resident)

        assertTrue("un résident non suivi ne doit pas être déclaré en sécurité", result.isFailure)
    }

    @Ignore("AUDIT C2 : une position sans coordonnées est remplacée par le centre de la zone (= « en sécurité »)")
    @Test
    fun `position without coordinates is not treated as inside the zone`() = runBlocking {
        val resident = insertResident()
        positionResponses.add(json("""[{"id":"1","latitude":null,"longitude":null,"battery":50}]"""))

        val result = repo.syncResidentPosition(resident)

        assertTrue(result.isFailure)
        assertEquals(null, db.residentDao().getResidentById(resident.id)!!.lastLatitude)
    }

    @Ignore("AUDIT C3 : lastUpdatedTime = heure de la requête, pas l'heure du fix GPS → une balise éteinte paraît « à l'instant »")
    @Test
    fun `last update time reflects the tracker fix time`() = runBlocking {
        val resident = insertResident()
        val fixTime = "2026-01-15T08:30:00+00:00"
        enqueuePosition(CENTER_LAT, CENTER_LON, dateTracker = fixTime)

        repo.syncResidentPosition(resident)

        val stored = db.residentDao().getResidentById(resident.id)!!
        assertEquals(OffsetDateTime.parse(fixTime).toInstant().toEpochMilli(), stored.lastUpdatedTime)
    }

    @Ignore("AUDIT M1 : les commandes (sonner, vibrer, SuperLive, refresh) renvoient un succès même si Weenect répond une erreur")
    @Test
    fun `ring command reports server errors`() = runBlocking {
        commandCode = 500
        val resident = insertResident()

        val result = repo.ringTracker(resident)

        assertTrue(result.isFailure)
    }

    @Test
    fun `ring command reaches the tracker endpoint`() = runBlocking {
        val resident = insertResident()

        val result = repo.ringTracker(resident)

        assertTrue(result.isSuccess)
        assertTrue(recordedPaths().any { it.endsWith("/mytracker/$TRACKER_ID/ring") })
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun insertResident(
        isInZone: Boolean = true,
        username: String = "famille@test.fr",
        password: String = "secret",
        trackerId: Long? = TRACKER_ID
    ): Resident {
        val r = Resident(
            name = "Jean Dupont",
            roomNumber = "Ch. 12",
            weenectUsername = username,
            weenectPassword = password,
            trackerId = trackerId,
            isInZone = isInZone
        )
        val id = db.residentDao().insertResident(r)
        return r.copy(id = id)
    }

    private fun enqueuePosition(lat: Double, lon: Double, dateTracker: String = "2026-10-02T10:00:00+00:00") {
        positionResponses.add(
            json(
                """[{"id":"p1","latitude":$lat,"longitude":$lon,"battery":80,"speed":0.0,
                   "type":"CMD-WBT-OUT","date_tracker":"$dateTracker","date_server":"$dateTracker",
                   "last_message":"$dateTracker"}]"""
            )
        )
    }

    private fun recordedPaths(): List<String> =
        (1..server.requestCount).map { server.takeRequest().path.orEmpty() }

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TRACKER_ID = 42L
        const val CENTER_LAT = 47.0
        const val CENTER_LON = -1.0
        const val OUTSIDE_LAT = 47.005 // ≈ 556 m au nord
    }
}
