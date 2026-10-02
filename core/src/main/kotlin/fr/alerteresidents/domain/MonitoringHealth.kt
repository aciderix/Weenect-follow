package fr.alerteresidents.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État réel du service de surveillance, partagé avec l'interface. */
data class HealthSnapshot(
    val serviceRunning: Boolean = false,
    val lastCycleAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val activeCount: Int = 0,
    val okCount: Int = 0,
    val errorCount: Int = 0,
    val consecutiveFailedCycles: Int = 0,
    val nextIntervalSeconds: Int = 15,
    val nightMode: Boolean = false
) {
    /** Toutes les synchros échouent depuis plusieurs cycles, ou aucun cycle depuis longtemps. */
    fun isDegraded(now: Long): Boolean =
        !serviceRunning ||
            consecutiveFailedCycles >= DEGRADED_AFTER_CYCLES ||
            (lastCycleAt != null && now - lastCycleAt > (nextIntervalSeconds * 1000L * 4).coerceAtLeast(120_000L))

    companion object {
        const val DEGRADED_AFTER_CYCLES = 3
    }
}

object MonitoringHealth {
    private val _state = MutableStateFlow(HealthSnapshot())
    val state: StateFlow<HealthSnapshot> = _state.asStateFlow()

    fun update(transform: (HealthSnapshot) -> HealthSnapshot) {
        synchronized(this) { _state.value = transform(_state.value) }
    }
}
