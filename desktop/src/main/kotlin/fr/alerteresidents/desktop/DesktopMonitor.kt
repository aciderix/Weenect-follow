package fr.alerteresidents.desktop

import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.remote.WeenectRepository
import fr.alerteresidents.desktop.data.DesktopStore
import fr.alerteresidents.desktop.platform.Platform
import fr.alerteresidents.domain.HealthSnapshot
import fr.alerteresidents.domain.MonitoringGap
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.util.DateParsing
import fr.alerteresidents.util.DesktopNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.logging.Logger

/** Boucle de surveillance du poste Windows (équivalent du service Android). */
class DesktopMonitor(
    private val store: DesktopStore,
    private val repo: WeenectRepository,
    private val notifier: () -> DesktopNotifier,
    /** Dernier cycle enregistré (persistant) et son enregistrement : repère les interruptions. */
    private val lastBeat: () -> Long = { 0L },
    private val saveBeat: (Long) -> Unit = {}
) {
    private val log = Logger.getLogger("DesktopMonitor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cycleMutex = Mutex()
    private var degradedNotified = false
    private var lastPurgeAt = 0L
    private var beat = 0L
    private var beatSavedAt = 0L
    private var interval = 15

    fun start() {
        if (job?.isActive == true) return
        MonitoringHealth.update { it.copy(serviceRunning = true) }
        job = scope.launch {
            beat = lastBeat()
            while (isActive) {
                // Poste mis en veille ou appli fermée depuis le dernier cycle : tracé dans le journal.
                runCatching { reportInterruption(System.currentTimeMillis()) }
                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warning("Erreur cycle : ${e.message}")
                }
                interval = store.zone.value.refreshIntervalFor(DateParsing.hourOf(System.currentTimeMillis()))
                MonitoringHealth.update { it.copy(nextIntervalSeconds = interval) }
                delay(interval * 1000L)
            }
        }
    }

    private suspend fun reportInterruption(now: Long) {
        val gap = MonitoringGap.detect(beat, now, interval)
        beat = now
        // Enregistré au plus une fois par minute (fichier de réglages réécrit à chaque fois).
        if (now - beatSavedAt >= 60_000L || gap != null) {
            beatSavedAt = now
            saveBeat(now)
        }
        if (gap == null) return
        val msg = gap.message(MonitoringGap.CAUSE_DESKTOP)
        store.insertAlert(AlertEvent(residentId = -1, residentName = "Surveillance", alertType = AlertType.MONITORING_DEGRADED, details = msg))
        notifier().warning("⚠️ Surveillance interrompue", msg)
    }

    fun refreshNow() {
        scope.launch { runCycle() }
    }

    fun stop() {
        job?.cancel()
        MonitoringHealth.update { it.copy(serviceRunning = false) }
    }

    suspend fun runCycle() {
        if (!cycleMutex.tryLock()) return
        try {
            doCycle()
        } finally {
            cycleMutex.unlock()
        }
    }

    private suspend fun doCycle() {
        val now = System.currentTimeMillis()
        Platform.keepAwake()
        val zone = store.zone.value
        val active = store.residents.value.filter { it.isTrackingActive }
        val night = zone.isNight(DateParsing.hourOf(now))
        if (!zone.isZoneActive || active.isEmpty()) {
            MonitoringHealth.update {
                it.copy(serviceRunning = true, lastCycleAt = now, activeCount = active.size, okCount = 0, errorCount = 0,
                    consecutiveFailedCycles = 0, nightMode = night)
            }
            return
        }
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            active.map { r ->
                async {
                    try {
                        semaphore.withPermit { repo.syncResidentPosition(r).isSuccess }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
            }.awaitAll()
        }
        val ok = results.count { it }
        val allFailed = ok == 0
        MonitoringHealth.update {
            it.copy(
                serviceRunning = true, lastCycleAt = now, lastSuccessAt = if (ok > 0) now else it.lastSuccessAt,
                activeCount = active.size, okCount = ok, errorCount = results.size - ok,
                consecutiveFailedCycles = if (allFailed) it.consecutiveFailedCycles + 1 else 0, nightMode = night
            )
        }
        val health = MonitoringHealth.state.value
        if (health.consecutiveFailedCycles >= HealthSnapshot.DEGRADED_AFTER_CYCLES && !degradedNotified) {
            degradedNotified = true
            val msg = "Aucune balise n'a pu être interrogée depuis ${health.consecutiveFailedCycles} cycles. Vérifiez la connexion internet du poste."
            store.insertAlert(AlertEvent(residentId = -1, residentName = "Toutes les balises", alertType = AlertType.MONITORING_DEGRADED, details = msg))
            notifier().warning("⚠️ Surveillance dégradée", msg)
        } else if (!allFailed) {
            degradedNotified = false
        }
        if (now - lastPurgeAt > 24 * 3_600_000L) {
            lastPurgeAt = now
            store.purgeOlderThan(now - 180L * 24 * 3_600_000L)
        }
    }
}
