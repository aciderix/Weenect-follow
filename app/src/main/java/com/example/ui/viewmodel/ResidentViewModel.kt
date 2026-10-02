package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NavDestination
import com.example.SecuriResidentApp
import com.example.data.model.AlertEvent
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import com.example.data.model.WeenectTrackerDto
import com.example.data.remote.SupabaseSyncService
import com.example.util.GeoUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ResidentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SecuriResidentApp
    private val residentDao = app.database.residentDao()
    private val zoneDao = app.database.facilityZoneDao()
    private val alertDao = app.database.alertEventDao()
    private val weenectRepo = app.weenectRepository
    private val soundAlertManager = app.soundAlertManager
    private val supabaseService = SupabaseSyncService()

    val residents: StateFlow<List<Resident>> = residentDao.getAllResidents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facilityZone: StateFlow<FacilityZone> = zoneDao.getFacilityZone()
        .filterNotNull()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            FacilityZone()
        )

    val allAlerts: StateFlow<List<AlertEvent>> = alertDao.getAllAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unacknowledgedAlerts: StateFlow<List<AlertEvent>> = alertDao.getUnacknowledgedAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedResident = MutableStateFlow<Resident?>(null)
    val selectedResident: StateFlow<Resident?> = _selectedResident.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isAlarmRinging = MutableStateFlow(false)
    val isAlarmRinging: StateFlow<Boolean> = _isAlarmRinging.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private var autoRefreshJob: Job? = null

    private val _pendingNavigation = MutableStateFlow<NavDestination?>(null)
    val pendingNavigation: StateFlow<NavDestination?> = _pendingNavigation.asStateFlow()

    init {
        viewModelScope.launch {
            val currentZone = zoneDao.getFacilityZoneOnce()
            if (currentZone == null) {
                zoneDao.insertOrUpdate(FacilityZone(name = "MAS l'Épeau"))
            } else if (currentZone.name == "Établissement Principal" || currentZone.name == "Centre d'Accueil Les Glycines" || currentZone.name == "Établissement") {
                zoneDao.insertOrUpdate(currentZone.copy(name = "MAS l'Épeau"))
            }
        }
        // Surveillance déléguée au ResidentMonitoringService pour éviter les doubles boucles et conflits
    }

    fun navigateToAlert(residentId: Long) {
        viewModelScope.launch {
            if (residentId != -1L) {
                var resident = residents.value.find { it.id == residentId }
                if (resident == null) {
                    resident = residentDao.getAllResidentsOnce().find { it.id == residentId }
                }
                if (resident != null) {
                    _selectedResident.value = resident
                }
            }
            _pendingNavigation.value = NavDestination.MAP
        }
    }

    fun clearPendingNavigation() {
        _pendingNavigation.value = null
    }

    fun selectResident(resident: Resident?) {
        _selectedResident.value = resident
    }

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    /**
     * Rafraîchissement manuel à la demande de l'utilisateur (Pull-to-refresh ou bouton).
     * Les alarmes sonores ne sont déclenchées qu'en cas de transition réelle par le WeenectRepository.
     */
    fun refreshAllPositions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isRefreshing.value = true
            val list = residents.value
            var successCount = 0
            var errorCount = 0
            for (resident in list) {
                if (resident.isTrackingActive) {
                    val result = weenectRepo.syncResidentPosition(resident)
                    if (result.isSuccess) {
                        successCount++
                    } else {
                        errorCount++
                    }
                }
            }
            if (showLoading) {
                _isRefreshing.value = false
                if (errorCount > 0) {
                    _operationMessage.value = "Actualisé : $successCount balise(s) OK, $errorCount en attente de signal"
                } else if (successCount > 0) {
                    _operationMessage.value = "$successCount balise(s) actualisée(s)"
                }
            }
        }
    }

    fun refreshSingleResident(resident: Resident) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = weenectRepo.syncResidentPosition(resident)
            if (res.isSuccess) {
                _operationMessage.value = "Position de ${resident.name} actualisée"
            } else {
                _operationMessage.value = "Signal Weenect en attente pour ${resident.name}"
            }
            _isRefreshing.value = false
        }
    }

    fun addOrUpdateResident(resident: Resident) {
        viewModelScope.launch {
            if (resident.id == 0L) {
                residentDao.insertResident(resident)
                _operationMessage.value = "Résident ${resident.name} ajouté avec succès"
            } else {
                residentDao.updateResident(resident)
                if (!resident.isTrackingActive) {
                    soundAlertManager.stopAlarm(resident.id)
                }
                _operationMessage.value = "Fiche de ${resident.name} mise à jour"
            }
            refreshAllPositions(showLoading = false)
        }
    }

    fun deleteResident(resident: Resident) {
        viewModelScope.launch {
            soundAlertManager.stopAlarm(resident.id)
            residentDao.deleteResident(resident)
            if (_selectedResident.value?.id == resident.id) {
                _selectedResident.value = null
            }
            _operationMessage.value = "${resident.name} supprimé"
        }
    }

    fun updateFacilityZone(zone: FacilityZone) {
        viewModelScope.launch {
            zoneDao.insertOrUpdate(zone)
            _operationMessage.value = "Zone de sécurité mise à jour (${zone.radiusMeters.toInt()}m)"
            // Re-évaluer les positions de tous les résidents avec le nouveau rayon
            refreshAllPositions(showLoading = false)
        }
    }

    fun ringTracker(resident: Resident) {
        viewModelScope.launch {
            _operationMessage.value = "Sonnerie envoyée à la balise de ${resident.name}..."
            val result = weenectRepo.ringTracker(resident)
            if (result.isSuccess) {
                _operationMessage.value = "Balise de ${resident.name} en cours de sonnerie"
            } else {
                _operationMessage.value = "Commande sonnerie transmise"
            }
        }
    }

    fun vibrateTracker(resident: Resident) {
        viewModelScope.launch {
            _operationMessage.value = "Vibration envoyée à la balise de ${resident.name}..."
            val result = weenectRepo.vibrateTracker(resident)
            if (result.isSuccess) {
                _operationMessage.value = "Balise de ${resident.name} vibre"
            } else {
                _operationMessage.value = "Commande vibration transmise"
            }
        }
    }

    fun activateSuperLive(resident: Resident) {
        viewModelScope.launch {
            _operationMessage.value = "Activation mode SuperLive (1s) pour ${resident.name}..."
            val result = weenectRepo.activateSuperLive(resident)
            if (result.isSuccess) {
                _operationMessage.value = "SuperLive activé pour ${resident.name}"
            } else {
                _operationMessage.value = "SuperLive en cours d'activation"
            }
        }
    }

    fun silenceAlarm(residentId: Long? = null) {
        soundAlertManager.stopAlarm(residentId)
        if (!soundAlertManager.isAlarmPlaying) {
            _isAlarmRinging.value = false
        }
    }

    fun acknowledgeAlert(alertId: Long, staffName: String = "Équipe Soins") {
        viewModelScope.launch {
            alertDao.acknowledgeAlert(alertId, staffName)
            if (unacknowledgedAlerts.value.size <= 1) {
                silenceAlarm()
            }
        }
    }

    fun acknowledgeAllAlerts() {
        viewModelScope.launch {
            alertDao.acknowledgeAllAlerts()
            silenceAlarm()
        }
    }

    /**
     * Test de simulation de sortie de zone (Exercice de sécurité établissement)
     * Déplace temporairement le résident à 300m en dehors de la zone pour tester l'alerte sur le téléphone.
     */
    fun simulateZoneExit(resident: Resident) {
        viewModelScope.launch {
            val zone = facilityZone.value
            val outsideLat = zone.centerLatitude + 0.0035 // ~400m au nord
            val outsideLon = zone.centerLongitude + 0.0015
            val dist = GeoUtils.calculateDistanceMeters(outsideLat, outsideLon, zone.centerLatitude, zone.centerLongitude)

            val updated = resident.copy(
                lastLatitude = outsideLat,
                lastLongitude = outsideLon,
                isInZone = false,
                distanceFromCenterMeters = dist,
                lastUpdatedTime = System.currentTimeMillis()
            )
            residentDao.updateResident(updated)

            alertDao.insertAlert(
                AlertEvent(
                    residentId = resident.id,
                    residentName = resident.name,
                    timestamp = System.currentTimeMillis(),
                    alertType = "EXIT_ZONE",
                    latitude = outsideLat,
                    longitude = outsideLon,
                    distanceMeters = dist
                )
            )

            _isAlarmRinging.value = true
            soundAlertManager.playZoneExitAlarm(
                resident = updated,
                distanceMeters = dist,
                soundEnabled = zone.soundAlertsEnabled,
                vibrateEnabled = zone.vibrateAlertsEnabled,
                forceMaxVolume = true
            )
            _operationMessage.value = "TEST : ${resident.name} simulé HORS ZONE (${dist.toInt()}m) - Sonnerie d'urgence déclenchée !"
        }
    }

    /**
     * Déclenche un test de sonnerie d'urgence maximale pour vérifier le haut-parleur et le vibreur.
     */
    fun triggerManualLoudAlarmTest() {
        val dummyResident = residents.value.firstOrNull() ?: Resident(name = "Test Exercice Sécurité", roomNumber = "Exercice")
        _isAlarmRinging.value = true
        soundAlertManager.playZoneExitAlarm(
            resident = dummyResident,
            distanceMeters = 250.0,
            soundEnabled = true,
            vibrateEnabled = true,
            forceMaxVolume = true
        )
        _operationMessage.value = "Sonnerie d'urgence maximale activée (Volume forcé à 100%)"
    }

    /**
     * Déclenche un test d'alarme différé de 6 secondes permettant à l'utilisateur de verrouiller l'écran
     * avec le bouton physique Power pour tester le réveil automatique.
     */
    fun triggerLockscreenDelayedAlarmTest(delaySeconds: Int = 6) {
        viewModelScope.launch {
            _operationMessage.value = "Test armé : VERROUILLEZ votre écran (bouton Power) ! Déclenchement dans $delaySeconds sec..."
            kotlinx.coroutines.delay(delaySeconds * 1000L)
            triggerManualLoudAlarmTest()
        }
    }

    fun simulateZoneReturn(resident: Resident) {
        resolveAlertForResident(resident)
    }

    /**
     * Lève immédiatement l'alerte pour un résident (marqué sécurisé dans l'établissement),
     * stoppe la sonnerie, ferme la notification et retire le bandeau d'alerte.
     */
    fun resolveAlertForResident(resident: Resident) {
        viewModelScope.launch {
            val zone = facilityZone.value
            val insideLat = zone.centerLatitude
            val insideLon = zone.centerLongitude
            val dist = 0.0

            val updated = resident.copy(
                lastLatitude = insideLat,
                lastLongitude = insideLon,
                isInZone = true,
                distanceFromCenterMeters = dist,
                lastUpdatedTime = System.currentTimeMillis()
            )
            residentDao.updateResident(updated)
            alertDao.insertAlert(
                AlertEvent(
                    residentId = resident.id,
                    residentName = resident.name,
                    timestamp = System.currentTimeMillis(),
                    alertType = "ENTER_ZONE",
                    latitude = insideLat,
                    longitude = insideLon,
                    distanceMeters = dist,
                    isAcknowledged = true,
                    acknowledgedBy = "Équipe Soins"
                )
            )
            silenceAlarm(resident.id)
            _operationMessage.value = "Alerte levée : ${resident.name} est en sécurité dans l'établissement"
        }
    }

    /**
     * Lève toutes les alertes actives et réinitialise tous les résidents en zone sûre.
     */
    fun resolveAllActiveAlerts() {
        viewModelScope.launch {
            val zone = facilityZone.value
            val currentResidents = residentDao.getAllResidentsOnce()
            for (res in currentResidents) {
                if (!res.isInZone) {
                    val updated = res.copy(
                        lastLatitude = zone.centerLatitude,
                        lastLongitude = zone.centerLongitude,
                        isInZone = true,
                        distanceFromCenterMeters = 0.0,
                        lastUpdatedTime = System.currentTimeMillis()
                    )
                    residentDao.updateResident(updated)
                }
            }
            alertDao.acknowledgeAllAlerts()
            silenceAlarm()
            _operationMessage.value = "Toutes les alertes ont été levées et acquittées"
        }
    }

    suspend fun testWeenectCredentials(username: String, pass: String): Result<List<WeenectTrackerDto>> {
        return weenectRepo.getTrackers(username, pass)
    }

    /**
     * Exporte la configuration complète (Zone + Résidents + Balises) et ouvre le partage Android.
     */
    fun exportConfiguration(context: Context, exportedBy: String = "Équipe Soignante") {
        viewModelScope.launch {
            val zone = facilityZone.value
            val resList = residentDao.getAllResidentsOnce()
            val success = com.example.util.ConfigBackupManager.exportAndShare(context, zone, resList, exportedBy)
            if (success) {
                _operationMessage.value = "Fichier de configuration prêt au partage"
            } else {
                _operationMessage.value = "Erreur lors de la génération de l'export"
            }
        }
    }

    /**
     * Importe une configuration complète avec option de remplacement ou de fusion.
     */
    fun importConfiguration(backupData: com.example.util.BackupData, replaceExisting: Boolean) {
        viewModelScope.launch {
            try {
                // 1. Mise à jour de la zone
                zoneDao.insertOrUpdate(backupData.facilityZone)

                // 2. Gestion des résidents
                if (replaceExisting) {
                    residentDao.deleteAllResidents()
                    for (res in backupData.residents) {
                        residentDao.insertResident(res)
                    }
                } else {
                    val existing = residentDao.getAllResidentsOnce()
                    for (res in backupData.residents) {
                        val duplicate = existing.find { 
                            (res.trackerId != null && it.trackerId == res.trackerId) || 
                            it.name.equals(res.name, ignoreCase = true) 
                        }
                        if (duplicate != null) {
                            // Mettre à jour avec les nouveaux identifiants
                            residentDao.updateResident(
                                duplicate.copy(
                                    weenectUsername = res.weenectUsername.ifBlank { duplicate.weenectUsername },
                                    weenectPassword = res.weenectPassword.ifBlank { duplicate.weenectPassword },
                                    trackerId = res.trackerId ?: duplicate.trackerId,
                                    trackerName = res.trackerName ?: duplicate.trackerName,
                                    roomNumber = res.roomNumber.ifBlank { duplicate.roomNumber },
                                    emergencyContact = res.emergencyContact.ifBlank { duplicate.emergencyContact },
                                    notes = res.notes.ifBlank { duplicate.notes }
                                )
                            )
                        } else {
                            residentDao.insertResident(res)
                        }
                    }
                }

                _operationMessage.value = "Configuration ${backupData.facilityZone.name} importée avec succès (${backupData.residents.size} résidents)"
                refreshAllPositions(showLoading = false)
            } catch (e: Exception) {
                e.printStackTrace()
                _operationMessage.value = "Erreur d'import : ${e.message}"
            }
        }
    }
}
