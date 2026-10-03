package fr.alerteresidents.cloud

import com.squareup.moshi.Moshi
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.remote.SharedStateHooks
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.data.store.ConfigStore
import fr.alerteresidents.data.store.ResidentStore
import fr.alerteresidents.data.store.ZoneStore
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.util.DateParsing
import fr.alerteresidents.util.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.logging.Logger

/** Réglages persistants (SharedPreferences sur Android, fichier de réglages sur Windows). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Ce que le partage peut faire sur l'alarme de l'appareil. */
interface CloudAlarmPort {
    /** Heure de début de l'alarme en cours pour ce résident, null s'il ne sonne pas. */
    fun alarmStartedAt(residentId: Long): Long?
    fun ring(resident: Resident, zone: FacilityZone, isDrill: Boolean, reportedBy: String?)
    fun stop(residentId: Long)
    fun info(title: String, message: String)
}

data class CloudDevice(
    val id: String,
    val name: String,
    val platform: String,
    val monitoringOk: Boolean,
    val residentsCount: Int,
    val lastSeenAt: Long?,
    val isThisDevice: Boolean
)

data class CloudState(
    val configured: Boolean = false,
    val url: String? = null,
    val email: String? = null,
    val deviceName: String? = null,
    val displayName: String? = null,
    val connected: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    /** La session ou l'autorisation doit être refaite par un humain (pas de nouvelle tentative utile). */
    val needsLogin: Boolean = false,
    val devices: List<CloudDevice> = emptyList(),
    val pendingChanges: Int = 0,
    /** Nombre de fiches résidents partagées (synchronisées automatiquement). */
    val sharedResidents: Int = 0,
    /** Phrase secrète de l'établissement enregistrée sur cet appareil. */
    val passphraseSet: Boolean = false,
    /** Des mots de passe Weenect partagés attendent la phrase secrète pour être utilisés ici. */
    val passphraseNeeded: Boolean = false
) {
    fun onlineDevices(now: Long): List<CloudDevice> = devices.filter { (it.lastSeenAt ?: 0) > now - ONLINE_MS }

    /**
     * Appareils à afficher : une ancienne installation (réinstallation de l'app, téléphone
     * réinitialisé) hors ligne et du même nom qu'un appareil en ligne n'est pas répétée.
     */
    fun displayedDevices(now: Long): List<CloudDevice> {
        val online = onlineDevices(now)
        return devices.filter { d ->
            d in online || online.none { it.name.equals(d.name, ignoreCase = true) && it.platform == d.platform }
        }
    }

    companion object {
        const val ONLINE_MS = 2 * 60_000L
    }
}

/**
 * Partage de l'état d'alerte entre les appareils d'un établissement via un projet Supabase
 * (n'importe lequel, initialisé avec supabase/migrations). Toujours « en plus » : la
 * surveillance locale continue exactement pareil si Supabase est absent ou injoignable.
 *
 * - les actions faites ici (sortie détectée, « Je m'en occupe », retrouvé, sortie accompagnée)
 *   sont envoyées dans l'ordre (file d'attente rejouée tant que l'envoi échoue) ;
 * - toutes les [pollMs] l'état partagé est relu : une prise en charge ailleurs coupe l'alarme
 *   ici, une sortie détectée ailleurs fait sonner ici (même pour un résident pas encore connu ici) ;
 * - les fiches résidents, comptes Weenect et la zone sont synchronisés automatiquement ([ConfigSync]).
 * Les alertes reconnaissent un résident d'un appareil à l'autre par l'identifiant de sa balise.
 */
class CloudSync(
    private val store: KeyValueStore,
    private val cipher: CredentialCipher,
    private val residents: ResidentStore,
    private val zones: ZoneStore,
    config: ConfigStore,
    private val repository: WeenectRepository,
    private val alarms: CloudAlarmPort,
    private val platform: String,
    private val appVersion: String,
    private val defaultDeviceName: String,
    private val staffName: () -> String,
    private val monitoringOk: () -> Boolean,
    private val http: OkHttpClient = HttpClients.base,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = 6_000L,
    private val errorBackoffMs: Long = 30_000L
) : SharedStateHooks {

    private val log = Logger.getLogger("CloudSync")
    private val moshi = Moshi.Builder().build()
    private val stateAdapter = moshi.adapter(SyncStateDto::class.java)
    private val incidentAdapter = moshi.adapter(IncidentDto::class.java)
    private val accessAdapter = moshi.adapter(AccessDto::class.java)
    private val configSync = ConfigSync(store, cipher, config)

    /** Alarmes de résidents inconnus ici (id négatif → balise), signalés par un autre appareil. */
    private val foreign = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var client: SupabaseClient? = null
    private var session: CloudSession? = null

    /** Changements faits ici, pas encore confirmés par Supabase (dans l'ordre). */
    private val outbox = ArrayDeque<Op>()
    private val outboxLock = Any()

    /** Incidents déjà traités ici (id:statut) et incidents ayant déjà fait sonner cet appareil. */
    private val applied = LinkedHashSet<String>()
    private val rung = LinkedHashSet<String>()
    private val openIncidents = HashMap<Long, MutableList<IncidentDto>>()
    private var cursor: String? = null

    val deviceId: String
        get() = store.get(K_DEVICE_ID) ?: UUID.randomUUID().toString().also { store.put(K_DEVICE_ID, it) }

    val isConfigured: Boolean get() = store.get(K_URL) != null && store.get(K_KEY) != null

    init {
        publishConfigured()
    }

    // ------------------------------------------------------------------------------------------
    // Connexion
    // ------------------------------------------------------------------------------------------

    /** Se connecte à un projet ; enregistre les réglages seulement si le compte est autorisé. */
    suspend fun connect(url: String, apiKey: String, email: String, password: String, deviceName: String): Result<String> =
        mutex.withLock {
            try {
                val base = SupabaseClient.normalizeUrl(url)
                    ?: return@withLock Result.failure(SupabaseException("Adresse du projet invalide (ex. https://abcd.supabase.co)"))
                if (apiKey.isBlank()) return@withLock Result.failure(SupabaseException("Clé publique (anon / publishable) manquante"))
                val c = SupabaseClient(base, apiKey.trim(), http, clock)
                val s = c.signIn(email, password)
                val access = accessAdapter.fromJson(c.rpc("check_access", emptyMap(), s.accessToken))
                if (access?.staff != true) {
                    c.signOut(s.accessToken)
                    return@withLock Result.failure(
                        SupabaseException(
                            "Compte reconnu mais pas autorisé. Dans Supabase (SQL Editor) : select public.add_staff('${email.trim()}', 'Nom');",
                            notStaff = true
                        )
                    )
                }
                if (store.get(K_URL) != base) configSync.reset(clearPassphrase = true)
                store.put(K_URL, base)
                store.put(K_KEY, apiKey.trim())
                store.put(K_EMAIL, s.email ?: email.trim())
                store.put(K_DEVICE_NAME, deviceName.trim().ifBlank { defaultDeviceName })
                store.put(K_DISPLAY_NAME, access.displayName)
                saveSession(s)
                client = c
                session = s
                cursor = null
                applied.clear()
                _state.value = baseState().copy(connected = true, displayName = access.displayName, error = null)
                wake.trySend(Unit)
                Result.success(access.displayName?.takeIf { it.isNotBlank() } ?: (s.email ?: email))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun disconnect() = mutex.withLock {
        session?.let { s -> client?.signOut(s.accessToken) }
        listOf(K_URL, K_KEY, K_EMAIL, K_REFRESH, K_DISPLAY_NAME).forEach { store.put(it, null) }
        configSync.reset(clearPassphrase = true)
        client = null
        session = null
        synchronized(outboxLock) { outbox.clear() }
        openIncidents.clear()
        _state.value = CloudState()
    }

    /** Phrase secrète de l'établissement (partage des mots de passe Weenect), vérifiée auprès du projet. */
    suspend fun setPassphrase(passphrase: String): Result<Unit> = mutex.withLock {
        runCatching {
            val (c, s) = ensureSession()
            configSync.setPassphrase(c, s.accessToken, passphrase)
            _state.value = _state.value.copy(passphraseSet = true, passphraseNeeded = false)
            wake.trySend(Unit)
            Unit
        }
    }

    fun setDeviceName(name: String) {
        store.put(K_DEVICE_NAME, name.trim().ifBlank { defaultDeviceName })
        _state.value = _state.value.copy(deviceName = deviceName())
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------------------------------
    // Boucle
    // ------------------------------------------------------------------------------------------

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val ok = runCatching { syncOnce() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
                withTimeoutOrNull(if (ok || !isConfigured) pollMs else errorBackoffMs) { wake.receive() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun syncSoon() {
        wake.trySend(Unit)
    }

    /** Un cycle : envoie les changements locaux puis applique l'état partagé. true si tout s'est bien passé. */
    suspend fun syncOnce(): Boolean = mutex.withLock {
        if (!isConfigured) {
            _state.value = CloudState()
            return@withLock true
        }
        try {
            val (c, s) = ensureSession()
            flushOutbox(c, s)
            // Fiches d'abord : un résident ajouté ailleurs est connu avant de lire les alertes.
            configSync.sync(c, s.accessToken, staffName())
            val raw = c.rpc(
                "sync_state",
                mapOf(
                    "p_device_id" to deviceId,
                    "p_device_name" to deviceName(),
                    "p_platform" to platform,
                    "p_app_version" to appVersion,
                    "p_monitoring_ok" to monitoringOk(),
                    "p_residents_count" to residents.getAllResidentsOnce().count { it.isTrackingActive },
                    "p_since" to cursor
                ),
                s.accessToken
            )
            val dto = stateAdapter.fromJson(raw) ?: throw SupabaseException("Réponse illisible")
            apply(dto)
            // Fenêtre de recouvrement : les changements validés pendant la lecture ne sont jamais perdus.
            cursor = DateParsing.parseIso(dto.now)?.let { DateParsing.formatIso(it - 30_000L) }
            _state.value = baseState().copy(
                connected = true,
                lastSyncAt = clock(),
                error = null,
                devices = dto.devices.map {
                    CloudDevice(it.id, it.name, it.platform, it.monitoringOk, it.residentsCount, DateParsing.parseIso(it.lastSeenAt), it.id == deviceId)
                },
                sharedResidents = configSync.sharedResidents,
                passphraseNeeded = configSync.passphraseNeeded
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val se = e as? SupabaseException
            if (se?.authExpired == true) {
                session = null
                if (se.message == SupabaseClient.SESSION_LOST) store.put(K_REFRESH, null)
            }
            log.warning("Partage : ${e.message}")
            _state.value = _state.value.copy(
                configured = true,
                connected = false,
                error = e.message ?: e.javaClass.simpleName,
                needsLogin = se?.notStaff == true || store.get(K_REFRESH) == null,
                pendingChanges = pendingCount()
            )
            false
        }
    }

    // ------------------------------------------------------------------------------------------
    // Alarmes de résidents inconnus sur cet appareil
    // ------------------------------------------------------------------------------------------

    /** « Je m'en occupe » sur une alarme d'un résident inconnu ici. false si ce n'en est pas une. */
    fun handleForeign(residentId: Long, staff: String): Boolean {
        val tracker = foreign.remove(residentId) ?: return false
        for (drill in drillFlags(tracker, localAlert = false)) enqueue(Op.Handle(tracker, drill, staff))
        return true
    }

    /** « Retrouvé » sur une alarme d'un résident inconnu ici. false si ce n'en est pas une. */
    fun resolveForeign(residentId: Long, staff: String): Boolean {
        val tracker = foreign.remove(residentId) ?: return false
        for (drill in drillFlags(tracker, localAlert = false)) enqueue(Op.Resolve(tracker, drill, staff, WeenectRepository.Resolution.FOUND))
        return true
    }

    private fun foreignId(tracker: Long) = FOREIGN_BASE - tracker

    // ------------------------------------------------------------------------------------------
    // Événements locaux → file d'envoi
    // ------------------------------------------------------------------------------------------

    override fun exitConfirmed(resident: Resident, isDrill: Boolean) {
        val tracker = resident.trackerId ?: return
        enqueue(
            Op.ReportExit(
                tracker, resident.name, isDrill, resident.id, resident.lastLatitude, resident.lastLongitude,
                resident.distanceFromCenterMeters
            )
        )
    }

    override fun handling(resident: Resident, staff: String, localAlert: Boolean) {
        val tracker = resident.trackerId ?: return
        for (drill in drillFlags(tracker, localAlert)) enqueue(Op.Handle(tracker, drill, staff))
    }

    override fun resolved(resident: Resident, staff: String, resolution: String, localAlert: Boolean) {
        val tracker = resident.trackerId ?: return
        for (drill in drillFlags(tracker, localAlert)) enqueue(Op.Resolve(tracker, drill, staff, resolution))
    }

    override fun pauseChanged(resident: Resident, untilMs: Long?, reason: String?, staff: String) {
        val tracker = resident.trackerId ?: return
        enqueue(Op.SetPause(tracker, resident.name, untilMs, reason, staff))
    }

    /**
     * Sortie vue ici → incident réel. Sinon (alarme d'exercice ou venue d'un autre appareil) :
     * tous les incidents ouverts connus pour cette balise.
     */
    private fun drillFlags(tracker: Long, localAlert: Boolean): List<Boolean> {
        if (localAlert) return listOf(false)
        val known = synchronized(openIncidents) { openIncidents[tracker]?.map { it.isDrill } }.orEmpty().distinct()
        return known.ifEmpty { listOf(true) }
    }

    private fun enqueue(op: Op) {
        if (!isConfigured) return
        synchronized(outboxLock) {
            outbox.addLast(op)
            while (outbox.size > MAX_OUTBOX) outbox.removeFirst()
        }
        wake.trySend(Unit)
    }

    private fun pendingCount() = synchronized(outboxLock) { outbox.size }

    private suspend fun flushOutbox(c: SupabaseClient, s: CloudSession) {
        while (true) {
            val op = synchronized(outboxLock) { outbox.firstOrNull() } ?: return
            val raw = c.rpc(op.function, op.args(this), s.accessToken)
            synchronized(outboxLock) { if (outbox.firstOrNull() === op) outbox.removeFirst() }
            if (op is Op.ReportExit) onExitReported(op, raw)
        }
    }

    /** Réponse à « sortie détectée » : l'incident a peut-être déjà été pris en charge ailleurs. */
    private suspend fun onExitReported(op: Op.ReportExit, raw: String) {
        val inc = runCatching { incidentAdapter.fromJson(raw) }.getOrNull() ?: return
        rung += inc.id
        rememberOpen(inc)
        if (inc.status == IncidentDto.HANDLING) {
            residents.getResidentById(op.residentId)?.let { onRemoteHandling(inc, it) }
            applied += "${inc.id}:${inc.status}"
        }
    }

    // ------------------------------------------------------------------------------------------
    // État partagé → cet appareil
    // ------------------------------------------------------------------------------------------

    private suspend fun apply(dto: SyncStateDto) {
        val now = clock()
        val byTracker = residents.getAllResidentsOnce().filter { it.trackerId != null }.groupBy { it.trackerId!! }
        val pending = synchronized(outboxLock) { outbox.map { it.tracker }.toSet() }
        val zone = zones.getFacilityZoneOnce() ?: FacilityZone()

        synchronized(openIncidents) {
            openIncidents.clear()
            dto.incidents.filter { it.status != IncidentDto.RESOLVED }.forEach { rememberOpenLocked(it) }
        }

        for (inc in dto.incidents) {
            val key = "${inc.id}:${inc.status}"
            if (key in applied) continue
            // Une action locale sur cette balise n'est pas encore partie : on attend qu'elle le soit.
            if (inc.trackerId in pending) continue
            // Une alarme « résident inconnu » a pu sonner avant que la fiche arrive : elle suit l'incident.
            if (inc.status != IncidentDto.ACTIVE) stopForeignAlarm(inc)
            val locals = byTracker[inc.trackerId].orEmpty()
            if (locals.isEmpty()) {
                onForeignIncident(inc, zone)
            } else for (r in locals) {
                when (inc.status) {
                    IncidentDto.ACTIVE -> onRemoteActive(inc, r, zone, now)
                    IncidentDto.HANDLING -> onRemoteHandling(inc, r)
                    IncidentDto.RESOLVED -> onRemoteResolved(inc, r)
                }
            }
            applied += key
        }

        for (p in dto.pauses) {
            val key = "pause:${p.trackerId}:${p.updatedAt}"
            if (key in applied || p.trackerId in pending) continue
            val until = DateParsing.parseIso(p.pausedUntil)?.takeIf { it > now }
            for (r in byTracker[p.trackerId].orEmpty()) {
                val changed = repository.applyRemotePause(r.id, until, p.reason, p.setBy ?: "?")
                if (changed != null && until != null && alarms.alarmStartedAt(r.id) != null) alarms.stop(r.id)
            }
            applied += key
        }
        trim(applied)
        trim(rung)
    }

    /**
     * Sortie signalée pour une balise qu'aucun résident de cet appareil n'utilise (fiche pas encore
     * reçue, ou résident non configuré ici) : on sonne quand même, avec le nom transmis.
     */
    private fun onForeignIncident(inc: IncidentDto, zone: FacilityZone) {
        val id = foreignId(inc.trackerId)
        when (inc.status) {
            IncidentDto.ACTIVE -> {
                if (inc.id in rung) return
                rung += inc.id
                foreign[id] = inc.trackerId
                val ghost = Resident(
                    id = id, name = inc.residentName.ifBlank { "Résident (balise ${inc.trackerId})" },
                    roomNumber = "Non suivi sur cet appareil", trackerId = inc.trackerId, isInZone = false
                )
                alarms.ring(ghost, zone, inc.isDrill, inc.openedByDeviceName)
            }
            else -> stopForeignAlarm(inc)
        }
    }

    private fun stopForeignAlarm(inc: IncidentDto) {
        val id = foreignId(inc.trackerId)
        foreign.remove(id)
        if (alarms.alarmStartedAt(id) == null) return
        alarms.stop(id)
        val who = if (inc.status == IncidentDto.HANDLING) inc.handledBy else inc.resolvedBy
        alarms.info("${inc.residentName} : alerte prise en main", "Par ${who ?: "un soignant"} — alarme coupée sur cet appareil")
    }

    private suspend fun onRemoteActive(inc: IncidentDto, r: Resident, zone: FacilityZone, now: Long) {
        if (inc.id in rung) return
        rung += inc.id
        if (inc.isDrill) {
            if (alarms.alarmStartedAt(r.id) == null) alarms.ring(r, zone, true, inc.openedByDeviceName)
            return
        }
        if (r.isPaused(now)) return
        // Cet appareil a déjà vu la sortie : son alarme locale suffit.
        if (!r.isInZone && (r.alertState == AlertState.ACTIVE || r.alertState == AlertState.HANDLING)) return
        // La balise a donné ici une position dans la zone plus récente que la sortie : retour confirmé.
        val opened = DateParsing.parseIso(inc.openedAt)
        val fix = r.lastUpdatedTime
        if (r.isInZone && r.lastSyncError == null && fix != null && opened != null && fix > opened + RETURN_MARGIN_MS) {
            enqueue(Op.Resolve(inc.trackerId, false, WeenectRepository.RETURN_BY_TRACKER, WeenectRepository.Resolution.RETURNED))
            return
        }
        alarms.ring(r, zone, false, inc.openedByDeviceName)
    }

    private suspend fun onRemoteHandling(inc: IncidentDto, r: Resident) {
        val at = DateParsing.parseIso(inc.handledAt) ?: clock()
        val stopped = stopIfOlder(r, at)
        if (!inc.isDrill && concernsCurrentExit(r, at)) repository.applyRemoteHandling(r.id, inc.handledBy ?: "?", null)
        if (stopped) alarms.info("${r.name} : pris(e) en charge", "Par ${inc.handledBy ?: "un soignant"} — alarme coupée sur cet appareil")
    }

    private suspend fun onRemoteResolved(inc: IncidentDto, r: Resident) {
        val at = DateParsing.parseIso(inc.resolvedAt) ?: clock()
        val stopped = stopIfOlder(r, at)
        if (!inc.isDrill && inc.resolution != WeenectRepository.Resolution.OUTING && concernsCurrentExit(r, at)) {
            repository.applyRemoteResolved(r.id, inc.resolvedBy ?: "?", inc.resolution ?: WeenectRepository.Resolution.FOUND, null)
        }
        if (stopped) {
            val what = when (inc.resolution) {
                WeenectRepository.Resolution.RETURNED -> "Retour dans la zone confirmé par la balise"
                WeenectRepository.Resolution.OUTING -> "Sortie accompagnée déclarée par ${inc.resolvedBy ?: "un soignant"}"
                else -> "Retrouvé(e) par ${inc.resolvedBy ?: "un soignant"}"
            }
            alarms.info("${r.name} : alerte levée", "$what — alarme coupée sur cet appareil")
        }
    }

    /** Ne coupe pas une alarme plus récente que l'événement (nouvelle sortie depuis). */
    private fun stopIfOlder(r: Resident, eventAt: Long): Boolean {
        val started = alarms.alarmStartedAt(r.id) ?: return false
        if (started > eventAt + CLOCK_MARGIN_MS) return false
        alarms.stop(r.id)
        return true
    }

    private fun concernsCurrentExit(r: Resident, eventAt: Long): Boolean {
        val exited = r.exitedAt ?: return true
        return eventAt >= exited - CLOCK_MARGIN_MS
    }

    private fun rememberOpen(inc: IncidentDto) = synchronized(openIncidents) { rememberOpenLocked(inc) }

    private fun rememberOpenLocked(inc: IncidentDto) {
        val list = openIncidents.getOrPut(inc.trackerId) { mutableListOf() }
        list.removeAll { it.id == inc.id }
        if (inc.status != IncidentDto.RESOLVED) list += inc
    }

    // ------------------------------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------------------------------

    private suspend fun ensureSession(): Pair<SupabaseClient, CloudSession> {
        val c = client ?: SupabaseClient(store.get(K_URL)!!, store.get(K_KEY)!!, http, clock).also { client = it }
        val current = session
        if (current != null && current.expiresAt - clock() > 60_000L) return c to current
        val refresh = current?.refreshToken ?: store.get(K_REFRESH)?.let { cipher.decrypt(it) }
            ?: throw SupabaseException(SupabaseClient.SESSION_LOST, authExpired = true)
        val s = c.refresh(refresh)
        saveSession(s)
        session = s
        return c to s
    }

    private fun saveSession(s: CloudSession) {
        store.put(K_REFRESH, cipher.encrypt(s.refreshToken))
    }

    private fun deviceName() = store.get(K_DEVICE_NAME) ?: defaultDeviceName

    private fun baseState() = CloudState(
        configured = isConfigured,
        url = store.get(K_URL),
        email = store.get(K_EMAIL),
        deviceName = deviceName(),
        displayName = store.get(K_DISPLAY_NAME),
        pendingChanges = pendingCount(),
        sharedResidents = configSync.sharedResidents,
        passphraseSet = configSync.passphraseSet,
        passphraseNeeded = configSync.passphraseNeeded
    )

    private fun publishConfigured() {
        _state.value = if (isConfigured) baseState() else CloudState()
    }

    private fun trim(set: LinkedHashSet<String>) {
        while (set.size > MAX_REMEMBERED) set.remove(set.first())
    }

    // ------------------------------------------------------------------------------------------

    private sealed class Op(val tracker: Long, val function: String) {
        abstract fun args(sync: CloudSync): Map<String, Any?>

        class ReportExit(
            tracker: Long, val name: String, val isDrill: Boolean, val residentId: Long,
            val lat: Double?, val lon: Double?, val distance: Double
        ) : Op(tracker, "report_exit") {
            override fun args(sync: CloudSync) = mapOf(
                "p_tracker_id" to tracker, "p_resident_name" to name, "p_is_drill" to isDrill,
                "p_latitude" to lat, "p_longitude" to lon, "p_distance_m" to distance,
                "p_device_id" to sync.deviceId, "p_device_name" to sync.deviceName()
            )
        }

        class Handle(tracker: Long, val isDrill: Boolean, val staff: String) : Op(tracker, "handle_incident") {
            override fun args(sync: CloudSync) = mapOf("p_tracker_id" to tracker, "p_is_drill" to isDrill, "p_staff" to staff)
        }

        class Resolve(tracker: Long, val isDrill: Boolean, val staff: String, val resolution: String) : Op(tracker, "resolve_incident") {
            override fun args(sync: CloudSync) =
                mapOf("p_tracker_id" to tracker, "p_is_drill" to isDrill, "p_staff" to staff, "p_resolution" to resolution)
        }

        class SetPause(tracker: Long, val name: String, val untilMs: Long?, val reason: String?, val staff: String) : Op(tracker, "set_pause") {
            override fun args(sync: CloudSync) = mapOf(
                "p_tracker_id" to tracker, "p_resident_name" to name,
                "p_until" to untilMs?.let { DateParsing.formatIso(it) }, "p_reason" to reason, "p_staff" to staff
            )
        }
    }

    companion object {
        /** Identifiants d'alarme des résidents inconnus ici : FOREIGN_BASE - balise (toujours négatifs). */
        const val FOREIGN_BASE = -1_000_000L
        const val K_URL = "cloud.url"
        const val K_KEY = "cloud.key"
        const val K_EMAIL = "cloud.email"
        const val K_REFRESH = "cloud.refresh"
        const val K_DEVICE_ID = "cloud.device_id"
        const val K_DEVICE_NAME = "cloud.device_name"
        const val K_DISPLAY_NAME = "cloud.display_name"
        private const val MAX_OUTBOX = 500
        private const val MAX_REMEMBERED = 2_000
        private const val RETURN_MARGIN_MS = 30_000L
        private const val CLOCK_MARGIN_MS = 120_000L
    }
}
