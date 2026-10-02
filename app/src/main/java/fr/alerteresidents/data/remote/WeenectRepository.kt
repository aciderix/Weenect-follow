package fr.alerteresidents.data.remote

import android.util.Log
import fr.alerteresidents.data.local.AlertEventDao
import fr.alerteresidents.data.local.FacilityZoneDao
import fr.alerteresidents.data.local.ResidentDao
import fr.alerteresidents.data.local.WeenectAccountDao
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentTracking
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.model.WeenectLoginRequest
import fr.alerteresidents.data.model.WeenectPositionDto
import fr.alerteresidents.data.model.WeenectTrackerDto
import fr.alerteresidents.domain.TransitionEvent
import fr.alerteresidents.domain.ZoneEvaluator
import fr.alerteresidents.domain.ZoneTransition
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.util.AppPreferences
import fr.alerteresidents.util.DateParsing
import fr.alerteresidents.util.HttpClients
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap

/** Réglages du moteur lus à chaque synchro (modifiables à chaud depuis les Paramètres). */
data class MonitoringSettings(val staleMinutes: Int = 15, val reminderMinutes: Int = 5)

/** Réactions de l'application aux événements détectés (alarme, notifications). */
interface MonitoringListener {
    fun onExitConfirmed(resident: Resident, zone: FacilityZone, isDrill: Boolean) {}
    fun onReminder(resident: Resident, zone: FacilityZone) {}
    fun onReturned(resident: Resident) {}
    fun onWarning(resident: Resident, type: String, message: String) {}
    fun onOutingEnded(resident: Resident) {}
    /** L'alarme de ce résident sonne-t-elle déjà ? (évite de relancer une alarme en cours) */
    fun isAlarmRinging(residentId: Long): Boolean = false
}

data class HistoryPoint(val latitude: Double, val longitude: Double, val time: Long?)

class WeenectRepository(
    private val residentDao: ResidentDao,
    private val facilityZoneDao: FacilityZoneDao,
    private val alertEventDao: AlertEventDao,
    private val accountDao: WeenectAccountDao,
    private val cipher: CredentialCipher,
    private val listener: MonitoringListener = object : MonitoringListener {},
    private val settings: () -> MonitoringSettings = { MonitoringSettings() },
    baseUrl: String = DEFAULT_BASE_URL,
    httpClient: OkHttpClient = HttpClients.weenect,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://apiv4.weenect.com/v4/"
        private const val TAG = "WeenectRepository"
        private const val BATTERY_RESET_MARGIN = 5
    }

    class WeenectException(message: String) : Exception(message)

    /** username -> "JWT <token>" */
    private val tokenCache = ConcurrentHashMap<String, String>()

    /** Un verrou par résident : la synchro et les actions des soignants ne s'écrasent jamais. */
    private val residentLocks = ConcurrentHashMap<Long, Mutex>()
    private fun lockFor(id: Long) = residentLocks.getOrPut(id) { Mutex() }

    private val api: WeenectApiService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
        .build()
        .create(WeenectApiService::class.java)

    // ------------------------------------------------------------------------------------------
    // Authentification et comptes
    // ------------------------------------------------------------------------------------------

    private data class Credentials(val username: String, val password: String)

    private suspend fun credentialsFor(resident: Resident): Credentials? {
        val account = resident.accountId?.let { accountDao.getById(it) } ?: return null
        val password = cipher.decrypt(account.encryptedPassword) ?: return null
        return Credentials(account.username, password)
    }

    suspend fun login(username: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(WeenectLoginRequest(username = username.trim(), password = pass))
            val accessToken = response.body()?.accessToken
            if (response.isSuccessful && accessToken != null) {
                val token = "JWT $accessToken"
                tokenCache[username.trim()] = token
                Result.success(token)
            } else if (response.code() == 401 || response.code() == 400 || response.code() == 403) {
                Result.failure(WeenectException("Identifiants Weenect refusés"))
            } else {
                Result.failure(WeenectException("Connexion Weenect impossible (code ${response.code()})"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(WeenectException("Réseau indisponible : ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Appel authentifié avec reconnexion automatique si le jeton a expiré. */
    private suspend fun <T> authorized(creds: Credentials, call: suspend (String) -> Response<T>): Result<Response<T>> {
        val token = tokenCache[creds.username] ?: login(creds.username, creds.password).getOrElse { return Result.failure(it) }
        var resp = call(token)
        if (resp.code() == 401) {
            tokenCache.remove(creds.username)
            val fresh = login(creds.username, creds.password).getOrElse { return Result.failure(it) }
            resp = call(fresh)
        }
        return Result.success(resp)
    }

    suspend fun getTrackers(username: String, pass: String): Result<List<WeenectTrackerDto>> = withContext(Dispatchers.IO) {
        try {
            val result = authorized(Credentials(username.trim(), pass)) { api.getTrackers(it) }
            val resp = result.getOrElse { return@withContext Result.failure(it) }
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.success(body.items)
            else Result.failure(WeenectException("Erreur récupération balises Weenect (${resp.code()})"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTrackersForAccount(accountId: Long): Result<List<WeenectTrackerDto>> {
        val account = accountDao.getById(accountId) ?: return Result.failure(WeenectException("Compte introuvable"))
        val password = cipher.decrypt(account.encryptedPassword)
            ?: return Result.failure(WeenectException("Mot de passe illisible : ressaisissez-le"))
        return getTrackers(account.username, password)
    }

    /** Crée ou met à jour un compte Weenect (mot de passe chiffré). */
    suspend fun saveAccount(label: String, username: String, password: String, existingId: Long? = null): Long {
        val encrypted = cipher.encrypt(password)
        val clean = username.trim()
        tokenCache.remove(clean)
        return if (existingId != null) {
            accountDao.update(WeenectAccount(existingId, label.ifBlank { clean }, clean, encrypted))
            existingId
        } else {
            accountDao.findByUsername(clean)?.let {
                accountDao.update(it.copy(label = label.ifBlank { it.label }, encryptedPassword = encrypted))
                return it.id
            }
            accountDao.insert(WeenectAccount(label = label.ifBlank { clean }, username = clean, encryptedPassword = encrypted))
        }
    }

    suspend fun deleteAccount(account: WeenectAccount) {
        residentDao.detachAccount(account.id)
        accountDao.delete(account)
        tokenCache.remove(account.username)
    }

    /**
     * Migre les identifiants stockés en clair dans les fiches (v1/v3) vers des comptes chiffrés.
     * Idempotent.
     */
    suspend fun migrateLegacyCredentials(): Int {
        var migrated = 0
        for (r in residentDao.getAllResidentsOnce()) {
            if (r.weenectUsername.isBlank() || r.weenectPassword.isBlank()) continue
            val accountId = saveAccount(r.weenectUsername, r.weenectUsername, r.weenectPassword)
            residentDao.attachAccount(r.id, accountId)
            migrated++
        }
        return migrated
    }

    // ------------------------------------------------------------------------------------------
    // Synchronisation
    // ------------------------------------------------------------------------------------------

    /**
     * Interroge la balise du résident et met à jour son état. Ne fabrique jamais de position :
     * en cas d'erreur, l'erreur est enregistrée (statut « inconnu » dans l'interface) et renvoyée.
     */
    suspend fun syncResidentPosition(resident: Resident): Result<Resident> = withContext(Dispatchers.IO) {
        lockFor(resident.id).withLock { syncLocked(resident.id) }
    }

    private suspend fun syncLocked(residentId: Long): Result<Resident> {
        var r = residentDao.getResidentById(residentId) ?: return Result.failure(WeenectException("Résident supprimé"))
        val now = clock()
        val cfg = settings()
        val zone = facilityZoneDao.getFacilityZoneOnce() ?: FacilityZone()

        if (!r.isTrackingActive) return Result.success(r)

        // Fin de sortie accompagnée : la surveillance reprend automatiquement.
        if (r.pausedUntil != null && r.pausedUntil!! <= now) {
            r = r.copy(pausedUntil = null, pauseReason = null)
            residentDao.updateTracking(ResidentTracking.of(r))
            log(r, AlertType.OUTING_END, acknowledged = true, details = "Reprise automatique de la surveillance")
            listener.onOutingEnded(r)
        }
        val paused = r.isPaused(now)

        val trackerId = r.trackerId
        val creds = credentialsFor(r)
        if (trackerId == null || creds == null) {
            val reason = when {
                trackerId == null -> "Aucune balise associée"
                r.accountId == null -> "Aucun compte Weenect associé"
                else -> "Mot de passe du compte Weenect illisible : ressaisissez-le"
            }
            return recordError(r, reason, now, cfg)
        }

        val positions: List<WeenectPositionDto> = try {
            val resp = authorized(creds) { api.getPositions(it, trackerId) }
                .getOrElse { return recordError(r, it.message ?: "Connexion Weenect impossible", now, cfg) }
            if (!resp.isSuccessful) return recordError(r, "Erreur Weenect (code ${resp.code()})", now, cfg)
            resp.body().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordError(r, "Réseau indisponible : ${e.message ?: e.javaClass.simpleName}", now, cfg)
        }

        val latest = positions.firstOrNull()
            ?: return recordError(r, "Aucune position reçue de la balise", now, cfg)
        val lat = latest.latitude
        val lon = latest.longitude
        if (lat == null || lon == null) return recordError(r, "Position reçue sans coordonnées", now, cfg)

        val fixTime = DateParsing.parseIso(latest.dateTracker)
            ?: DateParsing.parseIso(latest.lastMessage)
            ?: DateParsing.parseIso(latest.dateServer)
        val fixId = latest.id ?: fixTime?.toString() ?: "$lat,$lon"
        val eval = ZoneEvaluator.evaluate(zone, lat, lon, latest.radius, DateParsing.hourOf(now))

        var updated = r.copy(
            lastLatitude = lat,
            lastLongitude = lon,
            lastBattery = latest.battery ?: r.lastBattery,
            lastSpeed = latest.speed,
            lastUpdatedTime = fixTime,
            distanceFromCenterMeters = eval.distanceMeters,
            accuracyMeters = latest.radius,
            lastFixId = fixId,
            lastSyncTime = now,
            lastSyncError = null,
            lastSyncErrorAt = null,
            isInDeepSleep = latest.isInDeepSleep == true
        )

        // Transition dedans / dehors (suspendue pendant une sortie accompagnée)
        var event = TransitionEvent.NONE
        if (paused) {
            updated = updated.copy(isInZone = true, pendingExitFixId = null, pendingExitAt = null)
        } else {
            val t = ZoneTransition.apply(
                prev = updated.copy(isInZone = r.isInZone),
                eval = eval,
                fixId = fixId,
                now = now,
                reminderMs = cfg.reminderMinutes * 60_000L,
                alarmRinging = listener.isAlarmRinging(r.id)
            )
            updated = t.resident
            event = t.event
        }

        // Batterie faible (une seule alerte, réarmée quand la batterie remonte)
        val battery = updated.lastBattery
        if (battery != null && battery <= AppPreferences.LOW_BATTERY_THRESHOLD && !updated.lowBatteryNotified) {
            updated = updated.copy(lowBatteryNotified = true)
            log(updated, AlertType.LOW_BATTERY, details = "Batterie à $battery %")
            listener.onWarning(updated, AlertType.LOW_BATTERY, "Batterie de la balise à $battery %")
        } else if (battery != null && battery > AppPreferences.LOW_BATTERY_THRESHOLD + BATTERY_RESET_MARGIN && updated.lowBatteryNotified) {
            updated = updated.copy(lowBatteryNotified = false)
        }

        // Balise muette : l'API répond mais le dernier fix est trop ancien
        val staleMs = cfg.staleMinutes * 60_000L
        val fixAge = fixTime?.let { now - it }
        if ((fixAge == null || fixAge > staleMs) && !updated.offlineNotified && !paused) {
            updated = updated.copy(offlineNotified = true)
            val since = fixTime?.let { "depuis ${(fixAge!! / 60_000L)} min" } ?: "(heure du dernier signal inconnue)"
            log(updated, AlertType.TRACKER_OFFLINE, details = "Pas de nouvelle position $since")
            listener.onWarning(updated, AlertType.TRACKER_OFFLINE, "Aucune nouvelle position $since")
        } else if (fixAge != null && fixAge <= staleMs && updated.offlineNotified) {
            updated = updated.copy(offlineNotified = false)
        }

        residentDao.updateTracking(ResidentTracking.of(updated))

        when (event) {
            TransitionEvent.EXIT_CONFIRMED -> {
                log(updated, AlertType.EXIT_ZONE, distance = eval.distanceMeters)
                listener.onExitConfirmed(updated, zone, isDrill = false)
            }
            TransitionEvent.RETURNED -> {
                log(updated, AlertType.ENTER_ZONE, distance = eval.distanceMeters, acknowledged = true)
                listener.onReturned(updated)
            }
            TransitionEvent.REMINDER -> listener.onReminder(updated, zone)
            TransitionEvent.EXIT_PENDING -> {
                // Demande un nouveau fix tout de suite pour confirmer (ou infirmer) la sortie.
                runCatching { authorized(creds) { api.refreshPosition(it, trackerId) } }
            }
            TransitionEvent.NONE -> Unit
        }
        return Result.success(updated)
    }

    private suspend fun recordError(r: Resident, message: String, now: Long, cfg: MonitoringSettings): Result<Resident> {
        Log.w(TAG, "Synchro ${r.name} : $message")
        var updated = r.copy(lastSyncError = message, lastSyncErrorAt = now)
        // Injoignable depuis trop longtemps → avertissement (une seule fois)
        val lastOk = r.lastSyncTime
        if (!updated.offlineNotified && !r.isPaused(now) && (lastOk == null || now - lastOk > cfg.staleMinutes * 60_000L)) {
            val wasEverSynced = lastOk != null
            if (wasEverSynced || r.trackerId != null) {
                updated = updated.copy(offlineNotified = true)
                log(updated, AlertType.SYNC_ERROR, details = message)
                listener.onWarning(updated, AlertType.SYNC_ERROR, message)
            }
        }
        residentDao.updateTracking(ResidentTracking.of(updated))
        return Result.failure(WeenectException(message))
    }

    private suspend fun log(
        r: Resident,
        type: String,
        distance: Double = r.distanceFromCenterMeters,
        acknowledged: Boolean = false,
        details: String? = null,
        isDrill: Boolean = false,
        by: String? = null
    ) {
        val now = clock()
        alertEventDao.insertAlert(
            AlertEvent(
                residentId = r.id,
                residentName = r.name,
                timestamp = now,
                alertType = type,
                latitude = r.lastLatitude ?: 0.0,
                longitude = r.lastLongitude ?: 0.0,
                distanceMeters = distance,
                isAcknowledged = acknowledged,
                acknowledgedBy = if (acknowledged) by else null,
                acknowledgedAt = if (acknowledged) now else null,
                isDrill = isDrill,
                details = details
            )
        )
    }

    // ------------------------------------------------------------------------------------------
    // Actions des soignants (sous le même verrou que la synchro)
    // ------------------------------------------------------------------------------------------

    /** « Je m'en occupe » : acquitte la sortie et arrête les rappels, sans toucher à la position. */
    suspend fun markHandling(residentId: Long, staff: String): Resident? = mutate(residentId) { r ->
        if (r.isInZone) return@mutate null
        val now = clock()
        alertEventDao.acknowledgeForResident(r.id, AlertType.EXIT_ZONE, staff, now)
        log(r, AlertType.HANDLING, acknowledged = true, by = staff, details = "Pris en charge par $staff")
        r.copy(alertState = AlertState.HANDLING, alertHandledBy = staff, alertHandledAt = now)
    }

    /**
     * « Retrouvé » : l'alerte est levée mais la position reste celle de la balise. Le résident
     * repasse « dans la zone » dès que la balise le confirme.
     */
    suspend fun markFound(residentId: Long, staff: String): Resident? = mutate(residentId) { r ->
        if (r.isInZone) return@mutate null
        val now = clock()
        alertEventDao.acknowledgeForResident(r.id, AlertType.EXIT_ZONE, staff, now)
        log(r, AlertType.RESOLVED, acknowledged = true, by = staff, details = "Retrouvé par $staff")
        r.copy(alertState = AlertState.RESOLVED, alertHandledBy = staff, alertHandledAt = now)
    }

    /** Sortie accompagnée : la surveillance de zone est suspendue jusqu'à [untilMs]. */
    suspend fun startOuting(residentId: Long, untilMs: Long, reason: String, staff: String): Resident? = mutate(residentId) { r ->
        log(r, AlertType.OUTING_START, acknowledged = true, by = staff,
            details = "${reason.ifBlank { "Sortie accompagnée" }} — par $staff")
        val wasOut = !r.isInZone
        if (wasOut) alertEventDao.acknowledgeForResident(r.id, AlertType.EXIT_ZONE, staff)
        r.copy(
            pausedUntil = untilMs,
            pauseReason = reason.ifBlank { null },
            isInZone = true,
            exitedAt = null,
            alertState = AlertState.NONE,
            alertHandledBy = null,
            alertHandledAt = null,
            pendingExitFixId = null,
            pendingExitAt = null
        )
    }

    suspend fun endOuting(residentId: Long, staff: String): Resident? = mutate(residentId) { r ->
        if (r.pausedUntil == null) return@mutate null
        log(r, AlertType.OUTING_END, acknowledged = true, by = staff, details = "Fin de sortie — par $staff")
        r.copy(pausedUntil = null, pauseReason = null)
    }

    /** Exercice : déclenche l'alarme sans modifier le résident ; l'événement est marqué « exercice ». */
    suspend fun simulateExit(residentId: Long) {
        val r = residentDao.getResidentById(residentId) ?: return
        val zone = facilityZoneDao.getFacilityZoneOnce() ?: FacilityZone()
        log(r, AlertType.EXIT_ZONE, isDrill = true, details = "Exercice — simulation de sortie")
        listener.onExitConfirmed(r, zone, isDrill = true)
    }

    private suspend fun mutate(residentId: Long, block: suspend (Resident) -> Resident?): Resident? =
        withContext(Dispatchers.IO) {
            lockFor(residentId).withLock {
                val r = residentDao.getResidentById(residentId) ?: return@withLock null
                val updated = block(r) ?: return@withLock null
                residentDao.updateTracking(ResidentTracking.of(updated))
                updated
            }
        }

    // ------------------------------------------------------------------------------------------
    // Commandes balise
    // ------------------------------------------------------------------------------------------

    private suspend fun command(resident: Resident, call: suspend (String, Long) -> Response<Unit>): Result<Unit> =
        withContext(Dispatchers.IO) {
            val trackerId = resident.trackerId ?: return@withContext Result.failure(WeenectException("Aucune balise associée"))
            val creds = credentialsFor(resident) ?: return@withContext Result.failure(WeenectException("Aucun compte Weenect associé"))
            try {
                val resp = authorized(creds) { call(it, trackerId) }.getOrElse { return@withContext Result.failure(it) }
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(WeenectException("Weenect a refusé la commande (code ${resp.code()})"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(WeenectException("Réseau indisponible : ${e.message ?: e.javaClass.simpleName}"))
            }
        }

    suspend fun refreshLocation(resident: Resident) = command(resident) { t, id -> api.refreshPosition(t, id) }
    suspend fun ringTracker(resident: Resident) = command(resident) { t, id -> api.ring(t, id) }
    suspend fun vibrateTracker(resident: Resident) = command(resident) { t, id -> api.vibrate(t, id) }
    suspend fun activateSuperLive(resident: Resident) = command(resident) { t, id -> api.activateSuperLive(t, id) }

    /** Trajet des [hours] dernières heures, du plus ancien au plus récent. */
    suspend fun getHistory(resident: Resident, hours: Int): Result<List<HistoryPoint>> = withContext(Dispatchers.IO) {
        val trackerId = resident.trackerId ?: return@withContext Result.failure(WeenectException("Aucune balise associée"))
        val creds = credentialsFor(resident) ?: return@withContext Result.failure(WeenectException("Aucun compte Weenect associé"))
        try {
            val end = clock()
            val start = end - hours * 3_600_000L
            val resp = authorized(creds) {
                api.getPositions(it, trackerId, DateParsing.formatIso(start), DateParsing.formatIso(end))
            }.getOrElse { return@withContext Result.failure(it) }
            if (!resp.isSuccessful) return@withContext Result.failure(WeenectException("Erreur Weenect (code ${resp.code()})"))
            val points = resp.body().orEmpty().mapNotNull { p ->
                val lat = p.latitude ?: return@mapNotNull null
                val lon = p.longitude ?: return@mapNotNull null
                HistoryPoint(lat, lon, DateParsing.parseIso(p.dateTracker) ?: DateParsing.parseIso(p.lastMessage))
            }.sortedBy { it.time ?: 0L }
            Result.success(points)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
