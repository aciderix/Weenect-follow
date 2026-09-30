package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    init {
        viewModelScope.launch {
            val currentZone = zoneDao.getFacilityZoneOnce()
            if (currentZone == null) {
                zoneDao.insertOrUpdate(FacilityZone(name = "MAS l'Épeau"))
            } else if (currentZone.name == "Établissement Principal" || currentZone.name == "Centre d'Accueil Les Glycines" || currentZone.name == "Établissement") {
                zoneDao.insertOrUpdate(currentZone.copy(name = "MAS l'Épeau"))
            }
        }
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                val currentZone = zoneDao.getFacilityZoneOnce() ?: FacilityZone()
                val interval = currentZone.refreshIntervalSeconds.coerceAtLeast(10)
                delay(interval * 1000L)
                refreshAllPositions(showLoading = false)
            }
        }
    }

    fun selectResident(resident: Resident?) {
        _selectedResident.value = resident
    }

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    fun refreshAllPositions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isRefreshing.value = true
            val list = residents.value
            for (resident in list) {
                if (resident.isTrackingActive) {
                    val result = weenectRepo.syncResidentPosition(resident)
                    if (result.isSuccess) {
                        val updated = result.getOrNull()
                        if (updated != null && !updated.isInZone) {
                            _isAlarmRinging.value = true
                            soundAlertManager.playZoneExitAlarm(
                                resident = updated,
                                distanceMeters = updated.distanceFromCenterMeters,
                                soundEnabled = facilityZone.value.soundAlertsEnabled,
                                vibrateEnabled = facilityZone.value.vibrateAlertsEnabled,
                                forceMaxVolume = true
                            )
                        }
                    }
                }
            }
            if (showLoading) _isRefreshing.value = false
        }
    }

    fun refreshSingleResident(resident: Resident) {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = weenectRepo.syncResidentPosition(resident)
            if (res.isSuccess) {
                _operationMessage.value = "Position de ${resident.name} actualisée"
                val updated = res.getOrNull()
                if (updated != null && !updated.isInZone) {
                    _isAlarmRinging.value = true
                    soundAlertManager.playZoneExitAlarm()
                }
            } else {
                _operationMessage.value = "Erreur: ${res.exceptionOrNull()?.message}"
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
                _operationMessage.value = "Fiche de ${resident.name} mise à jour"
            }
            refreshAllPositions(showLoading = false)
        }
    }

    fun deleteResident(resident: Resident) {
        viewModelScope.launch {
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
            startAutoRefresh()
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

    fun silenceAlarm() {
        soundAlertManager.stopAlarm()
        _isAlarmRinging.value = false
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

    fun simulateZoneReturn(resident: Resident) {
        viewModelScope.launch {
            val zone = facilityZone.value
            val insideLat = zone.centerLatitude + 0.0002
            val insideLon = zone.centerLongitude + 0.0002
            val dist = GeoUtils.calculateDistanceMeters(insideLat, insideLon, zone.centerLatitude, zone.centerLongitude)

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
                    distanceMeters = dist
                )
            )
            silenceAlarm()
            _operationMessage.value = "${resident.name} de retour dans l'établissement"
        }
    }

    suspend fun testWeenectCredentials(username: String, pass: String): Result<List<WeenectTrackerDto>> {
        return weenectRepo.getTrackers(username, pass)
    }
}
