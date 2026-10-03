package fr.alerteresidents.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.alerteresidents.NavDestination
import fr.alerteresidents.SecuriResidentApp
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.model.WeenectTrackerDto
import fr.alerteresidents.data.remote.HistoryPoint
import fr.alerteresidents.domain.HealthSnapshot
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.security.KeystoreCredentialCipher
import fr.alerteresidents.service.ResidentMonitoringService
import fr.alerteresidents.util.AlarmInfo
import fr.alerteresidents.util.BackupData
import fr.alerteresidents.cloud.CloudState
import fr.alerteresidents.util.ConfigBackupManager
import fr.alerteresidents.util.DashboardViewMode
import fr.alerteresidents.util.JournalExporter
import fr.alerteresidents.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResidentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SecuriResidentApp
    private val residentDao = app.database.residentDao()
    private val zoneDao = app.database.facilityZoneDao()
    private val alertDao = app.database.alertEventDao()
    private val accountDao = app.database.weenectAccountDao()
    private val repo = app.weenectRepository
    private val alarms = app.soundAlertManager
    val prefs = app.preferences
    private val cipher = KeystoreCredentialCipher()

    val residents: StateFlow<List<Resident>> = residentDao.getAllResidents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facilityZone: StateFlow<FacilityZone> = zoneDao.getFacilityZone()
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FacilityZone())

    val allAlerts: StateFlow<List<AlertEvent>> = alertDao.getAllAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unacknowledgedAlerts: StateFlow<List<AlertEvent>> = alertDao.getUnacknowledgedAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<WeenectAccount>> = accountDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Alarmes en cours (source : SoundAlertManager), réelles comme d'exercice. */
    val activeAlarms: StateFlow<Map<Long, AlarmInfo>> = alarms.activeAlarms

    val health: StateFlow<HealthSnapshot> = MonitoringHealth.state

    /** Horloge rafraîchie toutes les 20 s pour « sorti depuis X min », signal ancien, etc. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(20_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    private val _selectedResidentId = MutableStateFlow<Long?>(null)
    val selectedResidentId: StateFlow<Long?> = _selectedResidentId.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _pendingNavigation = MutableStateFlow<NavDestination?>(null)
    val pendingNavigation: StateFlow<NavDestination?> = _pendingNavigation.asStateFlow()

    /** Paramètres déverrouillés par le code PIN pour la session en cours. */
    private val _settingsUnlocked = MutableStateFlow(false)
    val settingsUnlocked: StateFlow<Boolean> = _settingsUnlocked.asStateFlow()

    private val _history = MutableStateFlow<Pair<Long, List<HistoryPoint>>?>(null)
    val history: StateFlow<Pair<Long, List<HistoryPoint>>?> = _history.asStateFlow()

    val staffName get() = prefs.staffDisplayName

    fun statusOf(r: Resident, at: Long = now.value): ResidentStatus =
        ResidentStatusResolver.resolve(r, at, prefs.staleMinutes)

    // ------------------------------------------------------------------ navigation / sélection

    fun navigateToAlert(residentId: Long) {
        if (residentId > 0) _selectedResidentId.value = residentId
        _pendingNavigation.value = NavDestination.MAP
    }

    fun navigateTo(destination: NavDestination, residentId: Long? = null) {
        residentId?.takeIf { it > 0 }?.let { _selectedResidentId.value = it }
        _pendingNavigation.value = destination
    }

    fun clearPendingNavigation() {
        _pendingNavigation.value = null
    }

    fun selectResident(residentId: Long?) {
        _selectedResidentId.value = residentId
    }

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    fun showMessage(msg: String) {
        _operationMessage.value = msg
    }

    // ------------------------------------------------------------------ synchronisation

    /** Actualisation manuelle (en parallèle, sous les mêmes verrous que le service). */
    fun refreshAllPositions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isRefreshing.value = true
            val list = residents.value.ifEmpty { residentDao.getAllResidentsOnce() }
                .filter { it.isTrackingActive && !it.isPaused() }
            val semaphore = Semaphore(4)
            val results = list.map { r -> async { semaphore.withPermit { repo.syncResidentPosition(r) } } }.awaitAll()
            if (showLoading) {
                _isRefreshing.value = false
                val ok = results.count { it.isSuccess }
                val failed = results.size - ok
                _operationMessage.value = when {
                    results.isEmpty() -> "Aucun résident suivi"
                    failed == 0 -> "$ok balise(s) actualisée(s)"
                    ok == 0 -> "Échec : aucune balise joignable (${results.first().exceptionOrNull()?.message})"
                    else -> "$ok balise(s) OK, $failed en erreur"
                }
            }
        }
    }

    fun refreshSingleResident(resident: Resident) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repo.syncResidentPosition(resident)
            _operationMessage.value = res.fold(
                { "Position de ${resident.name} actualisée" },
                { "${resident.name} : ${it.message}" }
            )
            _isRefreshing.value = false
        }
    }

    // ------------------------------------------------------------------ fiches résidents

    /** Résidents qui utilisent déjà cette balise (pour l'avertissement de doublon). */
    suspend fun residentsUsingTracker(trackerId: Long, excludeId: Long): List<Resident> =
        residentDao.findByTracker(trackerId, excludeId)

    fun saveResident(profile: ResidentProfile, previousPhoto: String?) {
        viewModelScope.launch {
            if (profile.id == 0L) {
                val id = residentDao.insertResident(
                    Resident(
                        name = profile.name, roomNumber = profile.roomNumber, photoUri = profile.photoUri,
                        avatarColorHex = profile.avatarColorHex, accountId = profile.accountId,
                        trackerId = profile.trackerId, trackerName = profile.trackerName,
                        emergencyContact = profile.emergencyContact, notes = profile.notes,
                        isTrackingActive = profile.isTrackingActive, unit = profile.unit, riskLevel = profile.riskLevel
                    )
                )
                _operationMessage.value = "Résident ${profile.name} ajouté"
                app.cloudSync.syncSoon()
                residentDao.getResidentById(id)?.let { repo.syncResidentPosition(it) }
            } else {
                residentDao.updateProfile(profile)
                if (!profile.isTrackingActive) alarms.stopAlarm(profile.id)
                _operationMessage.value = "Fiche de ${profile.name} mise à jour"
                app.cloudSync.syncSoon()
                residentDao.getResidentById(profile.id)?.let { repo.syncResidentPosition(it) }
            }
            if (previousPhoto != null && previousPhoto != profile.photoUri) PhotoStore.delete(previousPhoto)
        }
    }

    fun importPhoto(context: Context, uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { PhotoStore.importPhoto(context, uri) }
            if (path == null) _operationMessage.value = "Photo illisible"
            onResult(path)
        }
    }

    fun deleteResident(resident: Resident) {
        viewModelScope.launch {
            alarms.stopAlarm(resident.id)
            residentDao.deleteResident(resident)
            PhotoStore.delete(resident.photoUri)
            if (_selectedResidentId.value == resident.id) _selectedResidentId.value = null
            _operationMessage.value = "${resident.name} supprimé(e)"
            app.cloudSync.syncSoon()
        }
    }

    fun updateFacilityZone(zone: FacilityZone) {
        viewModelScope.launch {
            zoneDao.insertOrUpdate(zone)
            _operationMessage.value = "Zone de sécurité mise à jour"
            app.cloudSync.syncSoon()
            refreshAllPositions(showLoading = false)
        }
    }

    // ------------------------------------------------------------------ commandes balise

    private fun command(resident: Resident, label: String, ok: String, block: suspend (Resident) -> Result<Unit>) {
        viewModelScope.launch {
            _operationMessage.value = "$label : envoi à la balise de ${resident.name}…"
            _operationMessage.value = block(resident).fold(
                { ok },
                { "$label impossible pour ${resident.name} : ${it.message}" }
            )
        }
    }

    fun ringTracker(r: Resident) = command(r, "Sonnerie", "Balise de ${r.name} : sonnerie demandée ✔") { repo.ringTracker(it) }
    fun vibrateTracker(r: Resident) = command(r, "Vibration", "Balise de ${r.name} : vibration demandée ✔") { repo.vibrateTracker(it) }
    fun activateSuperLive(r: Resident) = command(r, "SuperLive", "SuperLive activé pour ${r.name} ✔") { repo.activateSuperLive(it) }

    // ------------------------------------------------------------------ alertes

    /** Coupe le son d'un résident (ou de tous). */
    fun silenceAlarm(residentId: Long? = null) {
        val name = residentId?.let { id -> activeAlarms.value[id]?.residentName }
        val remaining = alarms.stopAlarm(residentId)
        _operationMessage.value = when {
            remaining > 0 && name != null -> "Alarme de $name coupée — $remaining autre(s) alarme(s) en cours"
            name != null -> "Alarme de $name coupée"
            else -> "Toutes les alarmes sont coupées"
        }
    }

    /** « Je m'en occupe » : acquitte, coupe l'alarme de ce résident, arrête les rappels. */
    fun handleAlert(residentId: Long) {
        if (app.cloudSync.handleForeign(residentId, staffName)) {
            alarms.stopAlarm(residentId)
            _operationMessage.value = "Prise en charge signalée aux autres appareils ($staffName)"
            return
        }
        viewModelScope.launch {
            val staff = staffName
            val r = repo.markHandling(residentId, staff)
            alarms.stopAlarm(residentId)
            _operationMessage.value = r?.let { "${it.name} : pris en charge par $staff" } ?: "Alarme coupée"
        }
    }

    /** « Retrouvé » : lève l'alerte sans modifier la position réelle. */
    fun markFound(residentId: Long) {
        if (app.cloudSync.resolveForeign(residentId, staffName)) {
            alarms.stopAlarm(residentId)
            _operationMessage.value = "Résident signalé retrouvé aux autres appareils"
            return
        }
        viewModelScope.launch {
            val r = repo.markFound(residentId, staffName)
            alarms.stopAlarm(residentId)
            _operationMessage.value = r?.let { "${it.name} retrouvé(e) — en attente de confirmation par la balise" }
                ?: "Le résident est déjà dans la zone"
        }
    }

    fun acknowledgeAlert(alertId: Long) {
        viewModelScope.launch { alertDao.acknowledgeAlert(alertId, staffName) }
    }

    fun acknowledgeAllAlerts() {
        viewModelScope.launch {
            alertDao.acknowledgeAllAlerts(staffName)
            _operationMessage.value = "Toutes les alertes ont été acquittées par $staffName"
        }
    }

    fun purgeJournal(olderThanDays: Int) {
        viewModelScope.launch {
            val n = alertDao.purgeOlderThan(System.currentTimeMillis() - olderThanDays * 86_400_000L)
            _operationMessage.value = "$n événement(s) supprimé(s) du journal"
        }
    }

    // ------------------------------------------------------------------ sorties accompagnées

    fun startOuting(residentIds: Collection<Long>, durationMinutes: Int, reason: String) {
        viewModelScope.launch {
            val until = System.currentTimeMillis() + durationMinutes * 60_000L
            var count = 0
            for (id in residentIds) {
                if (repo.startOuting(id, until, reason, staffName) != null) {
                    alarms.stopAlarm(id)
                    count++
                }
            }
            val end = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(until))
            _operationMessage.value = "Sortie accompagnée : $count résident(s) jusqu'à $end"
        }
    }

    fun endOuting(residentId: Long) {
        viewModelScope.launch {
            val r = repo.endOuting(residentId, staffName)
            _operationMessage.value = r?.let { "Surveillance de ${it.name} réactivée" } ?: "Aucune sortie en cours"
            r?.let { repo.syncResidentPosition(it) }
        }
    }

    // ------------------------------------------------------------------ exercices

    fun simulateZoneExit(resident: Resident) {
        viewModelScope.launch {
            repo.simulateExit(resident.id)
            _operationMessage.value = "EXERCICE : alarme de sortie déclenchée pour ${resident.name}"
        }
    }

    fun triggerManualLoudAlarmTest() {
        alarms.playTestAlarm(facilityZone.value)
        _operationMessage.value = "Test : sonnerie d'urgence au volume maximal"
    }

    fun triggerLockscreenDelayedAlarmTest(delaySeconds: Int = 6) {
        viewModelScope.launch {
            _operationMessage.value = "Test armé : verrouillez l'écran ! Déclenchement dans $delaySeconds s…"
            delay(delaySeconds * 1000L)
            triggerManualLoudAlarmTest()
        }
    }

    // ------------------------------------------------------------------ prise de poste

    fun validateShiftCheck(summary: String) {
        viewModelScope.launch {
            alertDao.insertAlert(
                AlertEvent(
                    residentId = -1, residentName = staffName, alertType = AlertType.SHIFT_CHECK,
                    isAcknowledged = true, acknowledgedBy = staffName, acknowledgedAt = System.currentTimeMillis(),
                    details = summary
                )
            )
            prefs.lastShiftCheck = System.currentTimeMillis()
            _operationMessage.value = "Prise de poste enregistrée dans le journal"
        }
    }

    // ------------------------------------------------------------------ comptes Weenect

    suspend fun testWeenectCredentials(username: String, pass: String): Result<List<WeenectTrackerDto>> =
        repo.getTrackers(username, pass)

    suspend fun trackersForAccount(accountId: Long): Result<List<WeenectTrackerDto>> =
        repo.getTrackersForAccount(accountId)

    fun saveAccount(label: String, username: String, password: String, existingId: Long?, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.saveAccount(label, username, password, existingId)
            _operationMessage.value = "Compte Weenect enregistré (mot de passe chiffré)"
            app.cloudSync.syncSoon()
            onSaved(id)
            refreshAllPositions(showLoading = false)
        }
    }

    fun deleteAccount(account: WeenectAccount) {
        viewModelScope.launch {
            repo.deleteAccount(account)
            _operationMessage.value = "Compte ${account.label} supprimé : les résidents associés ne sont plus suivis"
            app.cloudSync.syncSoon()
        }
    }

    // ------------------------------------------------------------------ préférences

    fun setStaffName(name: String) = prefs.setStaffName(name)
    fun setViewMode(mode: DashboardViewMode) = prefs.setViewMode(mode)
    fun setGroupByUnit(enabled: Boolean) = prefs.setGroupByUnit(enabled)

    fun unlockSettings(pin: String): Boolean {
        val ok = prefs.verifyPin(pin)
        if (ok) _settingsUnlocked.value = true
        return ok
    }

    fun lockSettings() {
        _settingsUnlocked.value = false
    }

    fun setPin(pin: String?) {
        prefs.setPin(pin)
        _settingsUnlocked.value = true
        _operationMessage.value = if (pin.isNullOrBlank()) "Code PIN supprimé" else "Code PIN enregistré"
    }

    // ------------------------------------------------------------------ carte : trajet

    fun loadHistory(resident: Resident, hours: Int) {
        viewModelScope.launch {
            repo.getHistory(resident, hours).fold(
                {
                    _history.value = resident.id to it
                    if (it.isEmpty()) _operationMessage.value = "Aucune position sur les $hours dernières heures"
                },
                { _operationMessage.value = "Trajet indisponible : ${it.message}" }
            )
        }
    }

    fun clearHistory() {
        _history.value = null
    }

    // ------------------------------------------------------------------ export / import

    fun exportConfiguration(context: Context, exportedBy: String, passphrase: String?) {
        viewModelScope.launch {
            val zone = facilityZone.value
            val resList = residentDao.getAllResidentsOnce()
            val accs = accountDao.getAllOnce().map { a ->
                a to if (passphrase.isNullOrBlank()) null else cipher.decrypt(a.encryptedPassword)
            }
            val json = withContext(Dispatchers.Default) {
                ConfigBackupManager.createBackupJson(zone, resList, exportedBy, accs, passphrase)
            }
            val ok = ConfigBackupManager.exportAndShare(context, json, zone.name, resList.size)
            _operationMessage.value = when {
                !ok -> "Erreur lors de la génération de l'export"
                passphrase.isNullOrBlank() -> "Export prêt (sans mots de passe Weenect)"
                else -> "Export prêt (mots de passe chiffrés par votre code)"
            }
        }
    }

    /**
     * Importe une configuration. [passphrase] déchiffre les mots de passe d'un export v2 ;
     * un export v1 (mots de passe en clair) est converti en comptes chiffrés.
     */
    fun importConfiguration(backupData: BackupData, replaceExisting: Boolean, passphrase: String?) {
        viewModelScope.launch {
            try {
                val secrets = if (backupData.hasEncryptedPasswords && !passphrase.isNullOrBlank()) {
                    ConfigBackupManager.decryptSecrets(backupData, passphrase)
                        ?: run {
                            _operationMessage.value = "Code incorrect : import annulé"
                            return@launch
                        }
                } else emptyMap()

                val existingZone = zoneDao.getFacilityZoneOnce()
                zoneDao.insertOrUpdate(backupData.facilityZone.copy(id = existingZone?.id ?: 1))

                // Comptes : ref du fichier → id local
                val refToId = mutableMapOf<Long, Long>()
                for (acc in backupData.accounts) {
                    val pwd = secrets[acc.ref]
                    val existing = accountDao.findByUsername(acc.username)
                    refToId[acc.ref] = when {
                        pwd != null -> repo.saveAccount(acc.label, acc.username, pwd, existing?.id)
                        existing != null -> existing.id
                        else -> repo.saveAccount(acc.label, acc.username, "") // mot de passe à ressaisir
                    }
                }

                if (replaceExisting) {
                    residentDao.getAllResidentsOnce().forEach { PhotoStore.delete(it.photoUri) }
                    residentDao.deleteAllResidents()
                }
                val existing = residentDao.getAllResidentsOnce()
                for (res in backupData.residents) {
                    val mapped = res.copy(accountId = res.accountId?.let { refToId[it] })
                    // Fusion : même balise, sinon même nom ET même chambre (pas de fusion sur homonyme seul)
                    val duplicate = existing.find {
                        (mapped.trackerId != null && it.trackerId == mapped.trackerId) ||
                            (it.name.equals(mapped.name, true) && it.roomNumber.equals(mapped.roomNumber, true))
                    }
                    if (duplicate != null) {
                        residentDao.updateProfile(
                            ResidentProfile(
                                id = duplicate.id,
                                name = duplicate.name,
                                roomNumber = mapped.roomNumber.ifBlank { duplicate.roomNumber },
                                photoUri = duplicate.photoUri,
                                avatarColorHex = duplicate.avatarColorHex,
                                accountId = mapped.accountId ?: duplicate.accountId,
                                trackerId = mapped.trackerId ?: duplicate.trackerId,
                                trackerName = mapped.trackerName ?: duplicate.trackerName,
                                emergencyContact = mapped.emergencyContact.ifBlank { duplicate.emergencyContact },
                                notes = mapped.notes.ifBlank { duplicate.notes },
                                isTrackingActive = mapped.isTrackingActive,
                                unit = mapped.unit.ifBlank { duplicate.unit },
                                riskLevel = maxOf(mapped.riskLevel, duplicate.riskLevel)
                            )
                        )
                        if (mapped.weenectPassword.isNotBlank()) {
                            residentDao.updateResident(
                                residentDao.getResidentById(duplicate.id)!!.copy(
                                    weenectUsername = mapped.weenectUsername, weenectPassword = mapped.weenectPassword
                                )
                            )
                        }
                    } else {
                        residentDao.insertResident(mapped)
                    }
                }
                if (backupData.hasLegacyPlaintextCredentials) repo.migrateLegacyCredentials()

                val pwdNote = when {
                    backupData.hasLegacyPlaintextCredentials -> " (ancien format : mots de passe convertis et chiffrés)"
                    backupData.accounts.isNotEmpty() && secrets.isEmpty() -> " — ressaisissez les mots de passe Weenect dans Paramètres › Comptes"
                    else -> ""
                }
                _operationMessage.value = "Configuration « ${backupData.facilityZone.name} » importée (${backupData.residents.size} résidents)$pwdNote"
                app.cloudSync.syncSoon()
                refreshAllPositions(showLoading = false)
            } catch (e: Exception) {
                e.printStackTrace()
                _operationMessage.value = "Erreur d'import : ${e.message}"
            }
        }
    }

    fun exportJournal(context: Context, pdf: Boolean, events: List<AlertEvent>) {
        viewModelScope.launch {
            try {
                val zoneName = facilityZone.value.name
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(Date())
                val uri = withContext(Dispatchers.IO) {
                    if (pdf) ConfigBackupManager.writeExportBytes(context, "journal_alertes_$stamp.pdf", JournalExporter.toPdf(events, zoneName, staffName))
                    else ConfigBackupManager.writeExport(context, "journal_alertes_$stamp.csv", JournalExporter.toCsv(events))
                }
                JournalExporter.share(context, uri, if (pdf) "application/pdf" else "text/csv", "Journal des alertes — $zoneName")
            } catch (e: Exception) {
                _operationMessage.value = "Export impossible : ${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------ partage entre appareils (Supabase)

    private val cloudSync = app.cloudSync
    val cloud: StateFlow<CloudState> = cloudSync.state
    val cloudDefaultDeviceName: String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Téléphone" }

    suspend fun connectCloud(url: String, key: String, email: String, password: String, deviceName: String): Result<String> =
        cloudSync.connect(url, key, email, password, deviceName).onSuccess {
            _operationMessage.value = "Partage activé : connecté en tant que $it"
        }

    fun disconnectCloud() {
        viewModelScope.launch {
            cloudSync.disconnect()
            _operationMessage.value = "Partage désactivé sur cet appareil"
        }
    }

    fun setCloudDeviceName(name: String) = cloudSync.setDeviceName(name)

    /** Phrase secrète de l'établissement : permet de partager les mots de passe Weenect entre appareils. */
    suspend fun setCloudPassphrase(passphrase: String): Result<Unit> =
        cloudSync.setPassphrase(passphrase).onSuccess {
            _operationMessage.value = "Phrase secrète enregistrée : les comptes Weenect sont partagés"
        }

    fun restartMonitoringService(context: Context) {
        ResidentMonitoringService.start(context)
        _operationMessage.value = "Service de surveillance relancé"
    }

    init {
        // Le service est la seule boucle permanente ; l'écran se contente d'afficher son état.
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (!health.value.serviceRunning) ResidentMonitoringService.start(getApplication())
            }
        }
    }
}
