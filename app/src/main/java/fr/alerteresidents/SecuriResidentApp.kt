package fr.alerteresidents

import android.app.Application
import fr.alerteresidents.data.local.AppDatabase
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.remote.MonitoringListener
import fr.alerteresidents.data.remote.MonitoringSettings
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.security.KeystoreCredentialCipher
import fr.alerteresidents.util.AppPreferences
import fr.alerteresidents.util.SoundAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SecuriResidentApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var soundAlertManager: SoundAlertManager
        private set

    lateinit var weenectRepository: WeenectRepository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferences = AppPreferences(this)
        soundAlertManager = SoundAlertManager(this, soundChoice = { preferences.alarmSound.value })
        val notifications = soundAlertManager.notificationHelper

        weenectRepository = WeenectRepository(
            residentDao = database.residentDao(),
            facilityZoneDao = database.facilityZoneDao(),
            alertEventDao = database.alertEventDao(),
            accountDao = database.weenectAccountDao(),
            cipher = KeystoreCredentialCipher(),
            settings = { MonitoringSettings(preferences.staleMinutes, preferences.reminderMinutes) },
            listener = object : MonitoringListener {
                override fun onExitConfirmed(resident: Resident, zone: FacilityZone, isDrill: Boolean) =
                    soundAlertManager.playZoneExitAlarm(resident, zone, isDrill = isDrill)

                override fun onReminder(resident: Resident, zone: FacilityZone) =
                    soundAlertManager.playZoneExitAlarm(resident, zone, isReminder = true)

                override fun onReturned(resident: Resident) {
                    soundAlertManager.stopAlarm(resident.id)
                    notifications.showInfo(resident, "✅ ${resident.name} est revenu(e) dans la zone", "Position confirmée par la balise")
                }

                override fun onWarning(resident: Resident, type: String, message: String) =
                    notifications.showWarning(resident, type, message)

                override fun onOutingEnded(resident: Resident) =
                    notifications.showInfo(resident, "Fin de sortie accompagnée : ${resident.name}", "La surveillance de zone a repris automatiquement")

                override fun isAlarmRinging(residentId: Long) = soundAlertManager.isRinging(residentId)
            }
        )

        // Anciennes versions : identifiants Weenect en clair dans les fiches → comptes chiffrés.
        if (!preferences.legacyCredentialsMigrated) {
            appScope.launch {
                runCatching { weenectRepository.migrateLegacyCredentials() }
                    .onSuccess { preferences.legacyCredentialsMigrated = true }
            }
        }
    }
}
