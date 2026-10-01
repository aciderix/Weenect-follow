package com.example.data.remote

import android.util.Log
import com.example.data.local.AlertEventDao
import com.example.data.local.FacilityZoneDao
import com.example.data.local.ResidentDao
import com.example.data.model.AlertEvent
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import com.example.data.model.WeenectLoginRequest
import com.example.data.model.WeenectPositionDto
import com.example.data.model.WeenectTrackerDto
import com.example.util.GeoUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WeenectRepository(
    private val residentDao: ResidentDao,
    private val facilityZoneDao: FacilityZoneDao,
    private val alertEventDao: AlertEventDao,
    private val onZoneExitDetected: (resident: Resident, distance: Double) -> Unit = { _, _ -> }
) {
    private val tokenCache = ConcurrentHashMap<String, String>() // username -> JWT token

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val api: WeenectApiService = Retrofit.Builder()
        .baseUrl("https://apiv4.weenect.com/v4/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(WeenectApiService::class.java)

    /**
     * Authentifie et récupère le JWT pour un compte Weenect.
     */
    suspend fun login(username: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(WeenectLoginRequest(username = username, password = pass))
            if (response.isSuccessful && response.body()?.accessToken != null) {
                val token = "JWT " + response.body()!!.accessToken!!
                tokenCache[username] = token
                Result.success(token)
            } else {
                Result.failure(Exception("Échec de connexion Weenect (${response.code()}): ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Récupère la liste des balises rattachées au compte Weenect.
     */
    suspend fun getTrackers(username: String, pass: String): Result<List<WeenectTrackerDto>> = withContext(Dispatchers.IO) {
        try {
            var token = tokenCache[username]
            if (token == null) {
                val loginRes = login(username, pass)
                if (loginRes.isFailure) return@withContext Result.failure(loginRes.exceptionOrNull()!!)
                token = loginRes.getOrNull()!!
            }

            var resp = api.getTrackers(token)
            if (resp.code() == 401) {
                // Token expiré, re-login
                val loginRes = login(username, pass)
                if (loginRes.isFailure) return@withContext Result.failure(loginRes.exceptionOrNull()!!)
                token = loginRes.getOrNull()!!
                resp = api.getTrackers(token)
            }

            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!.items)
            } else {
                Result.failure(Exception("Erreur récupération balises Weenect (${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Interroge la position actuelle de la balise et met à jour le statut du résident dans la base locale.
     */
    suspend fun syncResidentPosition(resident: Resident): Result<Resident> = withContext(Dispatchers.IO) {
        val zone = facilityZoneDao.getFacilityZoneOnce() ?: FacilityZone()
        val username = resident.weenectUsername.trim()
        val password = resident.weenectPassword.trim()
        val trackerId = resident.trackerId

        // Si des identifiants réels sont fournis
        if (username.isNotEmpty() && password.isNotEmpty() && trackerId != null) {
            try {
                var token = tokenCache[username]
                if (token == null) {
                    val loginRes = login(username, password)
                    if (loginRes.isSuccess) {
                        token = loginRes.getOrNull()
                    }
                }

                if (token != null) {
                    var posResp = api.getPositions(token, trackerId)
                    if (posResp.code() == 401) {
                        val loginRes = login(username, password)
                        if (loginRes.isSuccess) {
                            token = loginRes.getOrNull()!!
                            posResp = api.getPositions(token, trackerId)
                        }
                    }

                    if (posResp.isSuccessful && !posResp.body().isNullOrEmpty()) {
                        val latestPos = posResp.body()!!.first()
                        val lat = latestPos.latitude ?: resident.lastLatitude ?: zone.centerLatitude
                        val lon = latestPos.longitude ?: resident.lastLongitude ?: zone.centerLongitude
                        val battery = latestPos.battery ?: resident.lastBattery ?: 100
                        val speed = latestPos.speed ?: 0.0

                        val (inZone, distance) = if (zone.zoneType == "POLYGON" && zone.getPolygonPoints().size >= 3) {
                            val polygon = zone.getPolygonPoints()
                            val inside = GeoUtils.isPointInPolygon(lat, lon, polygon)
                            val dist = if (inside) 0.0 else GeoUtils.distanceToPolygonBoundaryMeters(lat, lon, polygon)
                            Pair(!zone.isZoneActive || inside, dist)
                        } else {
                            val dist = GeoUtils.calculateDistanceMeters(lat, lon, zone.centerLatitude, zone.centerLongitude)
                            Pair(!zone.isZoneActive || dist <= zone.radiusMeters, dist)
                        }

                        checkAndFireAlerts(resident, inZone, distance, lat, lon)

                        val updated = resident.copy(
                            lastLatitude = lat,
                            lastLongitude = lon,
                            lastBattery = battery,
                            lastSpeed = speed,
                            lastUpdatedTime = System.currentTimeMillis(),
                            isInZone = inZone,
                            distanceFromCenterMeters = distance
                        )
                        residentDao.updateResident(updated)
                        return@withContext Result.success(updated)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WeenectRepository", "Live sync failed, will keep current position: ${e.message}")
            }
        }

        // Si mode démonstration ou balise configurée sans accès internet immédiat:
        // On calcule la distance avec la dernière position connue
        val currentLat = resident.lastLatitude ?: zone.centerLatitude
        val currentLon = resident.lastLongitude ?: zone.centerLongitude
        val (inZone, distance) = if (zone.zoneType == "POLYGON" && zone.getPolygonPoints().size >= 3) {
            val polygon = zone.getPolygonPoints()
            val inside = GeoUtils.isPointInPolygon(currentLat, currentLon, polygon)
            val dist = if (inside) 0.0 else GeoUtils.distanceToPolygonBoundaryMeters(currentLat, currentLon, polygon)
            Pair(!zone.isZoneActive || inside, dist)
        } else {
            val dist = GeoUtils.calculateDistanceMeters(currentLat, currentLon, zone.centerLatitude, zone.centerLongitude)
            Pair(!zone.isZoneActive || dist <= zone.radiusMeters, dist)
        }

        checkAndFireAlerts(resident, inZone, distance, currentLat, currentLon)

        val updated = resident.copy(
            lastLatitude = currentLat,
            lastLongitude = currentLon,
            lastBattery = resident.lastBattery ?: 85,
            lastUpdatedTime = resident.lastUpdatedTime ?: System.currentTimeMillis(),
            isInZone = inZone,
            distanceFromCenterMeters = distance
        )
        residentDao.updateResident(updated)
        Result.success(updated)
    }

    private suspend fun checkAndFireAlerts(
        resident: Resident,
        inZone: Boolean,
        distance: Double,
        lat: Double,
        lon: Double
    ) {
        // Détection de sortie de zone (si auparavant dans la zone et maintenant dehors)
        if (resident.isInZone && !inZone) {
            alertEventDao.insertAlert(
                AlertEvent(
                    residentId = resident.id,
                    residentName = resident.name,
                    timestamp = System.currentTimeMillis(),
                    alertType = "EXIT_ZONE",
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = distance
                )
            )
            onZoneExitDetected(resident, distance)
        } else if (!resident.isInZone && inZone) {
            alertEventDao.insertAlert(
                AlertEvent(
                    residentId = resident.id,
                    residentName = resident.name,
                    timestamp = System.currentTimeMillis(),
                    alertType = "ENTER_ZONE",
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = distance
                )
            )
        }
    }

    suspend fun refreshLocation(resident: Resident): Result<Unit> = withContext(Dispatchers.IO) {
        val username = resident.weenectUsername.trim()
        val password = resident.weenectPassword.trim()
        val trackerId = resident.trackerId ?: return@withContext Result.failure(Exception("Identifiant balise manquant"))
        try {
            val token = tokenCache[username] ?: login(username, password).getOrNull()
            if (token != null) {
                api.refreshPosition(token, trackerId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Connexion impossible"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ringTracker(resident: Resident): Result<Unit> = withContext(Dispatchers.IO) {
        val username = resident.weenectUsername.trim()
        val password = resident.weenectPassword.trim()
        val trackerId = resident.trackerId ?: return@withContext Result.failure(Exception("Identifiant balise manquant"))
        try {
            val token = tokenCache[username] ?: login(username, password).getOrNull()
            if (token != null) {
                api.ring(token, trackerId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Connexion impossible"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun vibrateTracker(resident: Resident): Result<Unit> = withContext(Dispatchers.IO) {
        val username = resident.weenectUsername.trim()
        val password = resident.weenectPassword.trim()
        val trackerId = resident.trackerId ?: return@withContext Result.failure(Exception("Identifiant balise manquant"))
        try {
            val token = tokenCache[username] ?: login(username, password).getOrNull()
            if (token != null) {
                api.vibrate(token, trackerId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Connexion impossible"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun activateSuperLive(resident: Resident): Result<Unit> = withContext(Dispatchers.IO) {
        val username = resident.weenectUsername.trim()
        val password = resident.weenectPassword.trim()
        val trackerId = resident.trackerId ?: return@withContext Result.failure(Exception("Identifiant balise manquant"))
        try {
            val token = tokenCache[username] ?: login(username, password).getOrNull()
            if (token != null) {
                api.activateSuperLive(token, trackerId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Connexion impossible"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
