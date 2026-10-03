package fr.alerteresidents.util

import fr.alerteresidents.security.PassphraseCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

enum class DashboardViewMode { DETAILED, COMPACT, GRID }

/** Réglages locaux du poste Windows (fichier reglages.properties, non exportés). Même API que sur Android. */
class AppPreferences(dir: File) {
    private val file = File(dir, "reglages.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun save() = synchronized(props) { file.outputStream().use { props.store(it, "Alerte Résidents — réglages du poste") } }
    private fun set(key: String, value: String?) {
        synchronized(props) { if (value == null) props.remove(key) else props.setProperty(key, value) }
        save()
    }
    private fun int(key: String, def: Int) = props.getProperty(key)?.toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: def

    private val _staffName = MutableStateFlow(props.getProperty("staff_name", ""))
    val staffName: StateFlow<String> = _staffName.asStateFlow()
    private val _viewMode = MutableStateFlow(runCatching { DashboardViewMode.valueOf(props.getProperty("dashboard_view", "COMPACT")) }.getOrDefault(DashboardViewMode.COMPACT))
    val viewMode: StateFlow<DashboardViewMode> = _viewMode.asStateFlow()
    private val _groupByUnit = MutableStateFlow(bool("group_by_unit", false))
    val groupByUnit: StateFlow<Boolean> = _groupByUnit.asStateFlow()
    private val _hasPin = MutableStateFlow(props.getProperty("settings_pin") != null)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()
    private val _alarmSound = MutableStateFlow(runCatching { AlarmSound.valueOf(props.getProperty("alarm_sound", "SIREN")) }.getOrDefault(AlarmSound.SIREN))
    val alarmSound: StateFlow<AlarmSound> = _alarmSound.asStateFlow()
    private val _forceMaxVolume = MutableStateFlow(bool("force_max_volume", true))
    /** Monter le volume de Windows au maximum lors d'une alarme. */
    val forceMaxVolume: StateFlow<Boolean> = _forceMaxVolume.asStateFlow()

    val staffDisplayName: String get() = _staffName.value.ifBlank { "Équipe soignante" }

    fun setStaffName(name: String) { set("staff_name", name.trim()); _staffName.value = name.trim() }
    fun setViewMode(mode: DashboardViewMode) { set("dashboard_view", mode.name); _viewMode.value = mode }
    fun setGroupByUnit(enabled: Boolean) { set("group_by_unit", enabled.toString()); _groupByUnit.value = enabled }
    fun setAlarmSound(sound: AlarmSound) { set("alarm_sound", sound.name); _alarmSound.value = sound }
    fun setForceMaxVolume(enabled: Boolean) { set("force_max_volume", enabled.toString()); _forceMaxVolume.value = enabled }

    var staleMinutes: Int
        get() = int("stale_minutes", 15)
        set(value) = set("stale_minutes", value.coerceIn(5, 240).toString())

    var reminderMinutes: Int
        get() = int("reminder_minutes", 5)
        set(value) = set("reminder_minutes", value.coerceIn(1, 60).toString())

    var legacyCredentialsMigrated: Boolean
        get() = bool("legacy_migrated", false)
        set(value) = set("legacy_migrated", value.toString())

    var lastShiftCheck: Long
        get() = props.getProperty("last_shift_check")?.toLongOrNull() ?: 0L
        set(value) = set("last_shift_check", value.toString())

    /** Dernier cycle de surveillance (repère une interruption : poste éteint, en veille, appli fermée). */
    var lastMonitoringBeat: Long
        get() = props.getProperty("monitoring_last_beat")?.toLongOrNull() ?: 0L
        set(value) = set("monitoring_last_beat", value.toString())

    /** Premier lancement : active le démarrage automatique une seule fois. */
    var autostartInitialized: Boolean
        get() = bool("autostart_initialized", false)
        set(value) = set("autostart_initialized", value.toString())

    fun setPin(pin: String?) {
        set("settings_pin", if (pin.isNullOrBlank()) null else PassphraseCrypto.hashPin(pin))
        _hasPin.value = !pin.isNullOrBlank()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = props.getProperty("settings_pin") ?: return true
        return PassphraseCrypto.verifyPin(pin, stored)
    }

    companion object {
        const val LOW_BATTERY_THRESHOLD = fr.alerteresidents.domain.Thresholds.LOW_BATTERY
    }
}
