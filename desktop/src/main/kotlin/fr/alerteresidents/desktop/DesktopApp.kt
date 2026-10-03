package fr.alerteresidents.desktop

import fr.alerteresidents.cloud.CloudAlarmPort
import fr.alerteresidents.cloud.CloudSync
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.remote.MonitoringListener
import fr.alerteresidents.data.remote.MonitoringSettings
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.desktop.data.DesktopStore
import fr.alerteresidents.desktop.platform.Autostart
import fr.alerteresidents.desktop.platform.DesktopCipher
import fr.alerteresidents.desktop.platform.Platform
import fr.alerteresidents.security.CredentialCipher
import fr.alerteresidents.util.AppPreferences
import fr.alerteresidents.util.DesktopNotifier
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.util.HttpClients
import fr.alerteresidents.util.PropertiesStore
import fr.alerteresidents.util.SoundAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Assemble les briques de l'app Windows (équivalent de l'Application Android). */
class DesktopApp(
    dataDir: File = Platform.dataDir,
    baseUrl: String = WeenectRepository.DEFAULT_BASE_URL,
    val cipher: CredentialCipher = DesktopCipher(dataDir)
) {
    val preferences = AppPreferences(dataDir)
    val store = DesktopStore(dataDir)
    var notifier: DesktopNotifier = object : DesktopNotifier {}
        set(value) {
            field = value
            alarms.notifier = value
        }
    val alarms = SoundAlertManager(
        soundChoice = { preferences.alarmSound.value },
        forceVolume = { preferences.forceMaxVolume.value }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: WeenectRepository = WeenectRepository(
        residentDao = store,
        facilityZoneDao = store,
        alertEventDao = store,
        accountDao = store,
        cipher = cipher,
        settings = { MonitoringSettings(preferences.staleMinutes, preferences.reminderMinutes) },
        baseUrl = baseUrl,
        listener = object : MonitoringListener {
            override fun onExitConfirmed(resident: Resident, zone: FacilityZone, isDrill: Boolean) =
                alarms.playZoneExitAlarm(resident, zone, isDrill = isDrill)

            override fun onReminder(resident: Resident, zone: FacilityZone) =
                alarms.playZoneExitAlarm(resident, zone, isReminder = true)

            override fun onReturned(resident: Resident) {
                alarms.stopAlarm(resident.id)
                notifier.info("✅ ${resident.name} est revenu(e) dans la zone", "Position confirmée par la balise")
            }

            override fun onWarning(resident: Resident, type: String, message: String) =
                notifier.warning("⚠️ ${fr.alerteresidents.data.model.AlertType.label(type)} — ${resident.name}", message)

            override fun onOutingEnded(resident: Resident) =
                notifier.info("Fin de sortie accompagnée : ${resident.name}", "La surveillance de zone a repris automatiquement")

            override fun isAlarmRinging(residentId: Long) = alarms.isRinging(residentId)
        }
    )

    val monitor = DesktopMonitor(store, repository) { notifier }

    /** Partage de l'état d'alerte avec les autres appareils (projet Supabase de l'établissement). */
    val cloudSync = CloudSync(
        store = PropertiesStore(File(dataDir, "partage.properties")),
        cipher = cipher,
        residents = store,
        zones = store,
        repository = repository,
        alarms = object : CloudAlarmPort {
            override fun alarmStartedAt(residentId: Long) = alarms.activeAlarms.value[residentId]?.startedAt
            override fun ring(resident: Resident, zone: FacilityZone, isDrill: Boolean, reportedBy: String?) =
                alarms.playZoneExitAlarm(resident, zone, isDrill = isDrill, reportedBy = reportedBy)
            override fun stop(residentId: Long) { alarms.stopAlarm(residentId) }
            override fun info(title: String, message: String) = notifier.info(title, message)
        },
        platform = "windows",
        appVersion = APP_VERSION,
        defaultDeviceName = defaultDeviceName(),
        staffName = { preferences.staffName.value.ifBlank { "Soignant" } },
        monitoringOk = { !MonitoringHealth.state.value.isDegraded(System.currentTimeMillis()) }
    )

    /** Démarre la surveillance et le partage. */
    fun start() {
        monitor.start()
        cloudSync.start(scope)
    }

    companion object {
        const val APP_VERSION = "2.0.0"

        fun defaultDeviceName(): String =
            (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())
                ?.takeIf { it.isNotBlank() }?.let { "PC $it" } ?: "PC"
    }

    init {
        repository.sharedHooks = cloudSync
        HttpClients.userAgent = "AlerteResidents/2.0 (Windows)"
        if (!preferences.legacyCredentialsMigrated) {
            scope.launch {
                runCatching { repository.migrateLegacyCredentials() }.onSuccess { preferences.legacyCredentialsMigrated = true }
            }
        }
        // Poste fixe : démarrage automatique activé au premier lancement de la version installée.
        if (!preferences.autostartInitialized && Autostart.isSupported) {
            if (Autostart.setEnabled(true)) preferences.autostartInitialized = true
        }
    }
}
