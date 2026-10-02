package fr.alerteresidents.util

import android.content.Context
import fr.alerteresidents.security.PassphraseCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashboardViewMode { DETAILED, COMPACT, GRID }

/** Réglages locaux du téléphone (non liés à l'établissement, donc non exportés). */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _staffName = MutableStateFlow(prefs.getString(KEY_STAFF, "") ?: "")
    /** Nom du soignant qui utilise ce téléphone (affiché sur les prises en charge et acquittements). */
    val staffName: StateFlow<String> = _staffName.asStateFlow()

    private val _viewMode = MutableStateFlow(
        runCatching { DashboardViewMode.valueOf(prefs.getString(KEY_VIEW, null) ?: "") }.getOrDefault(DashboardViewMode.DETAILED)
    )
    val viewMode: StateFlow<DashboardViewMode> = _viewMode.asStateFlow()

    private val _groupByUnit = MutableStateFlow(prefs.getBoolean(KEY_GROUP_UNIT, false))
    val groupByUnit: StateFlow<Boolean> = _groupByUnit.asStateFlow()

    private val _hasPin = MutableStateFlow(prefs.getString(KEY_PIN, null) != null)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    /** Nom affiché pour les actions ; jamais vide. */
    val staffDisplayName: String get() = _staffName.value.ifBlank { "Équipe soignante" }

    fun setStaffName(name: String) {
        prefs.edit().putString(KEY_STAFF, name.trim()).apply()
        _staffName.value = name.trim()
    }

    fun setViewMode(mode: DashboardViewMode) {
        prefs.edit().putString(KEY_VIEW, mode.name).apply()
        _viewMode.value = mode
    }

    fun setGroupByUnit(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROUP_UNIT, enabled).apply()
        _groupByUnit.value = enabled
    }

    var staleMinutes: Int
        get() = prefs.getInt(KEY_STALE, 15)
        set(value) = prefs.edit().putInt(KEY_STALE, value.coerceIn(5, 240)).apply()

    var reminderMinutes: Int
        get() = prefs.getInt(KEY_REMINDER, 5)
        set(value) = prefs.edit().putInt(KEY_REMINDER, value.coerceIn(1, 60)).apply()

    var legacyCredentialsMigrated: Boolean
        get() = prefs.getBoolean(KEY_LEGACY, false)
        set(value) = prefs.edit().putBoolean(KEY_LEGACY, value).apply()

    var lastShiftCheck: Long
        get() = prefs.getLong(KEY_SHIFT, 0L)
        set(value) = prefs.edit().putLong(KEY_SHIFT, value).apply()

    fun setPin(pin: String?) {
        if (pin.isNullOrBlank()) prefs.edit().remove(KEY_PIN).apply()
        else prefs.edit().putString(KEY_PIN, PassphraseCrypto.hashPin(pin)).apply()
        _hasPin.value = !pin.isNullOrBlank()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN, null) ?: return true
        return PassphraseCrypto.verifyPin(pin, stored)
    }

    companion object {
        /** Seuil unique de batterie faible, utilisé partout dans l'app. */
        const val LOW_BATTERY_THRESHOLD = 20

        private const val KEY_STAFF = "staff_name"
        private const val KEY_VIEW = "dashboard_view"
        private const val KEY_GROUP_UNIT = "group_by_unit"
        private const val KEY_PIN = "settings_pin"
        private const val KEY_STALE = "stale_minutes"
        private const val KEY_REMINDER = "reminder_minutes"
        private const val KEY_LEGACY = "legacy_credentials_migrated"
        private const val KEY_SHIFT = "last_shift_check"
    }
}
