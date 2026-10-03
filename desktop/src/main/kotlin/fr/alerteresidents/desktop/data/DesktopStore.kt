package fr.alerteresidents.desktop.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.ResidentTracking
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.store.AccountStore
import fr.alerteresidents.data.store.AlertStore
import fr.alerteresidents.data.store.ResidentStore
import fr.alerteresidents.data.store.ZoneStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * Stockage de l'app Windows : fichiers JSON dans le dossier de données (écriture atomique).
 * Mêmes opérations que les DAO Room de l'app Android.
 */
class DesktopStore(private val dir: File) : ResidentStore, ZoneStore, AlertStore, AccountStore {
    private val log = Logger.getLogger("DesktopStore")
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> listAdapter(): JsonAdapter<List<T>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, T::class.java))

    private val residentsAdapter = listAdapter<Resident>()
    private val alertsAdapter = listAdapter<AlertEvent>()
    private val accountsAdapter = listAdapter<WeenectAccount>()
    private val zoneAdapter = moshi.adapter(FacilityZone::class.java)

    private val _residents = MutableStateFlow(read("residents.json") { residentsAdapter.fromJson(it) }.orEmpty().sortedBy { it.name.lowercase() })
    private val _alerts = MutableStateFlow(read("journal.json") { alertsAdapter.fromJson(it) }.orEmpty().sortedByDescending { it.timestamp })
    private val _accounts = MutableStateFlow(read("comptes.json") { accountsAdapter.fromJson(it) }.orEmpty())
    private val _zone = MutableStateFlow(read("zone.json") { zoneAdapter.fromJson(it) } ?: FacilityZone())

    val residents: StateFlow<List<Resident>> = _residents.asStateFlow()
    val alerts: StateFlow<List<AlertEvent>> = _alerts.asStateFlow()
    val accounts: StateFlow<List<WeenectAccount>> = _accounts.asStateFlow()
    val zone: StateFlow<FacilityZone> = _zone.asStateFlow()

    private fun <T> read(name: String, parse: (String) -> T?): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            parse(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // Fichier illisible : on le met de côté plutôt que de l'écraser.
            log.severe("Fichier $name illisible : ${e.message}")
            f.copyTo(File(dir, "$name.illisible-${System.currentTimeMillis()}"), overwrite = true)
            null
        }
    }

    private fun write(name: String, json: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun saveResidents(list: List<Resident>) {
        _residents.value = list.sortedBy { it.name.lowercase() }
        write("residents.json", residentsAdapter.toJson(_residents.value))
    }

    private fun saveAlerts(list: List<AlertEvent>) {
        _alerts.value = list.sortedByDescending { it.timestamp }
        write("journal.json", alertsAdapter.toJson(_alerts.value))
    }

    private fun saveAccounts(list: List<WeenectAccount>) {
        _accounts.value = list.sortedBy { it.label.lowercase() }
        write("comptes.json", accountsAdapter.toJson(_accounts.value))
    }

    // ------------------------------------------------------------------ résidents

    override suspend fun getResidentById(id: Long): Resident? = _residents.value.find { it.id == id }
    override suspend fun getAllResidentsOnce(): List<Resident> = _residents.value

    override suspend fun updateTracking(tracking: ResidentTracking) = synchronized(this) {
        saveResidents(_residents.value.map { if (it.id == tracking.id) it.withTracking(tracking) else it })
    }

    override suspend fun attachAccount(id: Long, accountId: Long) = synchronized(this) {
        saveResidents(_residents.value.map {
            if (it.id == id) it.copy(accountId = accountId, weenectUsername = "", weenectPassword = "") else it
        })
    }

    override suspend fun detachAccount(accountId: Long) = synchronized(this) {
        saveResidents(_residents.value.map { if (it.accountId == accountId) it.copy(accountId = null) else it })
    }

    @Synchronized
    fun insertResident(r: Resident): Long {
        val id = (_residents.value.maxOfOrNull { it.id } ?: 0L) + 1
        saveResidents(_residents.value + r.copy(id = id))
        return id
    }

    @Synchronized
    fun updateProfile(p: ResidentProfile) {
        saveResidents(_residents.value.map {
            if (it.id == p.id) it.copy(
                name = p.name, roomNumber = p.roomNumber, photoUri = p.photoUri, avatarColorHex = p.avatarColorHex,
                accountId = p.accountId, trackerId = p.trackerId, trackerName = p.trackerName,
                emergencyContact = p.emergencyContact, notes = p.notes, isTrackingActive = p.isTrackingActive,
                unit = p.unit, riskLevel = p.riskLevel
            ) else it
        })
    }

    @Synchronized
    fun setResidentSyncId(id: Long, syncId: String) {
        saveResidents(_residents.value.map { if (it.id == id) it.copy(syncId = syncId) else it })
    }

    @Synchronized
    fun setLegacyCredentials(id: Long, username: String, password: String) {
        saveResidents(_residents.value.map { if (it.id == id) it.copy(weenectUsername = username, weenectPassword = password) else it })
    }

    @Synchronized
    fun deleteResident(id: Long) = saveResidents(_residents.value.filterNot { it.id == id })

    @Synchronized
    fun deleteAllResidents() = saveResidents(emptyList())

    fun findByTracker(trackerId: Long, excludeId: Long): List<Resident> =
        _residents.value.filter { it.trackerId == trackerId && it.id != excludeId }

    // ------------------------------------------------------------------ zone

    override suspend fun getFacilityZoneOnce(): FacilityZone = _zone.value

    @Synchronized
    fun saveZone(zone: FacilityZone) {
        _zone.value = zone.copy(id = 1)
        write("zone.json", zoneAdapter.toJson(_zone.value))
    }

    // ------------------------------------------------------------------ journal

    override suspend fun insertAlert(alert: AlertEvent): Long = synchronized(this) {
        val id = (_alerts.value.maxOfOrNull { it.id } ?: 0L) + 1
        saveAlerts(_alerts.value + alert.copy(id = id))
        return id
    }

    override suspend fun acknowledgeForResident(residentId: Long, type: String, staffName: String, at: Long) = synchronized(this) {
        saveAlerts(_alerts.value.map {
            if (!it.isAcknowledged && it.residentId == residentId && it.alertType == type)
                it.copy(isAcknowledged = true, acknowledgedBy = staffName, acknowledgedAt = at) else it
        })
    }

    @Synchronized
    fun acknowledgeAlert(id: Long, staffName: String, at: Long = System.currentTimeMillis()) {
        saveAlerts(_alerts.value.map { if (it.id == id) it.copy(isAcknowledged = true, acknowledgedBy = staffName, acknowledgedAt = at) else it })
    }

    @Synchronized
    fun acknowledgeAll(staffName: String, at: Long = System.currentTimeMillis()) {
        saveAlerts(_alerts.value.map { if (!it.isAcknowledged) it.copy(isAcknowledged = true, acknowledgedBy = staffName, acknowledgedAt = at) else it })
    }

    /** Supprime les événements anciens déjà traités (les alertes non acquittées sont conservées). */
    @Synchronized
    fun purgeOlderThan(before: Long): Int {
        val keep = _alerts.value.filter { it.timestamp >= before || (!it.isAcknowledged && it.alertType in AlertType.ACTIONABLE) }
        val removed = _alerts.value.size - keep.size
        if (removed > 0) saveAlerts(keep)
        return removed
    }

    // ------------------------------------------------------------------ comptes

    override suspend fun getById(id: Long): WeenectAccount? = _accounts.value.find { it.id == id }
    override suspend fun findByUsername(username: String): WeenectAccount? = _accounts.value.find { it.username == username }

    override suspend fun insert(account: WeenectAccount): Long = synchronized(this) {
        val id = (_accounts.value.maxOfOrNull { it.id } ?: 0L) + 1
        saveAccounts(_accounts.value + account.copy(id = id))
        return id
    }

    override suspend fun update(account: WeenectAccount) = synchronized(this) { saveAccounts(_accounts.value.map { if (it.id == account.id) account else it }) }

    override suspend fun delete(account: WeenectAccount) = synchronized(this) { saveAccounts(_accounts.value.filterNot { it.id == account.id }) }
}

fun Resident.withTracking(t: ResidentTracking) = copy(
    lastLatitude = t.lastLatitude, lastLongitude = t.lastLongitude, lastBattery = t.lastBattery, lastSpeed = t.lastSpeed,
    lastUpdatedTime = t.lastUpdatedTime, isInZone = t.isInZone, distanceFromCenterMeters = t.distanceFromCenterMeters,
    lastSyncTime = t.lastSyncTime, lastSyncError = t.lastSyncError, lastSyncErrorAt = t.lastSyncErrorAt,
    accuracyMeters = t.accuracyMeters, lastFixId = t.lastFixId, pendingExitFixId = t.pendingExitFixId,
    pendingExitAt = t.pendingExitAt, exitedAt = t.exitedAt, alertState = t.alertState, alertHandledBy = t.alertHandledBy,
    alertHandledAt = t.alertHandledAt, lastAlarmAt = t.lastAlarmAt, pausedUntil = t.pausedUntil, pauseReason = t.pauseReason,
    lowBatteryNotified = t.lowBatteryNotified, offlineNotified = t.offlineNotified, isInDeepSleep = t.isInDeepSleep
)
