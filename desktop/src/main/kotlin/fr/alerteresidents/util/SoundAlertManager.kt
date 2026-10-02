package fr.alerteresidents.util

import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.desktop.platform.AlarmPlayer
import fr.alerteresidents.desktop.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notifications Windows (bulles de la zone de notification) et mise au premier plan. */
interface DesktopNotifier {
    fun alert(title: String, message: String) {}
    fun warning(title: String, message: String) {}
    fun info(title: String, message: String) {}
    /** Une alarme commence : afficher la fenêtre au premier plan. */
    fun bringToFront() {}
}

/**
 * Alarmes de sortie de zone sur le poste Windows. Même API que la version Android.
 * Sur un poste fixe, le son est toujours joué (pas de vibreur pour le remplacer).
 */
class SoundAlertManager(
    soundChoice: () -> AlarmSound,
    forceVolume: () -> Boolean,
    var notifier: DesktopNotifier = object : DesktopNotifier {}
) {
    private val player = AlarmPlayer(soundChoice, forceVolume)
    private val lock = Any()
    private val _activeAlarms = MutableStateFlow<Map<Long, AlarmInfo>>(emptyMap())
    val activeAlarms: StateFlow<Map<Long, AlarmInfo>> = _activeAlarms.asStateFlow()

    val isAlarmPlaying: Boolean get() = _activeAlarms.value.isNotEmpty()
    fun isRinging(residentId: Long) = _activeAlarms.value.containsKey(residentId)

    fun playZoneExitAlarm(resident: Resident, zone: FacilityZone, isDrill: Boolean = false, isReminder: Boolean = false) {
        synchronized(lock) {
            _activeAlarms.value = _activeAlarms.value +
                (resident.id to AlarmInfo(resident.id, resident.name, isDrill, isReminder, System.currentTimeMillis()))
        }
        val prefix = when {
            isDrill -> "EXERCICE — "
            isReminder -> "RAPPEL — "
            else -> ""
        }
        val dist = GeoUtils.formatDistance(resident.distanceFromCenterMeters)
        notifier.alert(
            "${prefix}🚨 ${resident.name} HORS ZONE",
            (if (resident.hasPosition) "À $dist" + if (zone.isPolygon) " de la limite" else " du centre" else "") +
                " • ${resident.roomNumber.ifBlank { "chambre non renseignée" }}"
        )
        Platform.keepAwake(display = true)
        notifier.bringToFront()
        player.start()
    }

    fun playTestAlarm(zone: FacilityZone) =
        playZoneExitAlarm(Resident(id = TEST_ALARM_ID, name = "Test d'alarme", roomNumber = "Exercice"), zone, isDrill = true)

    /** Coupe l'alarme d'un résident (ou toutes) ; renvoie le nombre d'alarmes encore actives. */
    fun stopAlarm(residentId: Long? = null): Int {
        val remaining = synchronized(lock) {
            _activeAlarms.value = if (residentId == null) emptyMap() else _activeAlarms.value - residentId
            _activeAlarms.value.size
        }
        if (remaining == 0) player.stop()
        return remaining
    }

    companion object {
        const val TEST_ALARM_ID = -1L
    }
}
