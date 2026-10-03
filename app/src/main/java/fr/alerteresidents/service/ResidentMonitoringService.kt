package fr.alerteresidents.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.alerteresidents.MainActivity
import fr.alerteresidents.R
import fr.alerteresidents.SecuriResidentApp
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.domain.HealthSnapshot
import fr.alerteresidents.domain.MonitoringGap
import fr.alerteresidents.domain.MonitoringHealth
import fr.alerteresidents.domain.MonitoringSummary
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.util.DateParsing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Service au premier plan : surveillance continue des balises, même écran éteint ou app fermée.
 * L'état réel (synchros réussies / en échec) est publié dans [MonitoringHealth].
 */
class ResidentMonitoringService : Service() {

    // Une erreur imprévue dans une tâche ne doit jamais faire planter l'app (donc la surveillance).
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Erreur imprévue: ${e.message}", e) }
    )
    private var monitoringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cycleMutex = Mutex()
    private var degradedNotified = false
    private var lastPurgeAt = 0L
    @Volatile private var facilityName = "Alerte Résidents"
    /** Dernier texte de la notification permanente (réutilisé quand le service est relancé). */
    @Volatile private var statusText = "Démarrage de la surveillance…"
    @Volatile private var statusAlert = false
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createMonitoringNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlerteResidents:Monitoring")
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_NOW -> {
                ensureForeground()
                if (monitoringJob?.isActive != true) startMonitoringLoop()
                serviceScope.launch {
                    try {
                        runCycle()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur actualisation: ${e.message}")
                    }
                }
            }
            else -> {
                ensureForeground()
                if (monitoringJob?.isActive != true) startMonitoringLoop()
            }
        }
        // Filet de sécurité si Android arrête l'app : relance par alarme système.
        MonitoringWatchdog.schedule(this)
        return START_STICKY
    }

    /** Appli balayée des applis récentes : certains fabricants tuent alors le service → relance rapide. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        MonitoringWatchdog.schedule(this, 5_000L)
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Type « specialUse » uniquement : l'app interroge l'API Weenect et n'utilise pas le GPS du
     * téléphone, donc pas besoin du type « location » (refusé au démarrage depuis l'arrière-plan
     * sur Android 14+).
     */
    private fun ensureForeground() {
        val notification = buildOngoingNotification(statusText, statusAlert)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            MonitoringHealth.update { it.copy(serviceRunning = true) }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refused: ${e.message}")
            MonitoringHealth.update { it.copy(serviceRunning = false) }
        }
    }

    private fun startMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            val app = application as? SecuriResidentApp ?: return@launch
            runCatching { reportInterruption(app) }.onFailure { Log.e(TAG, "Trace interruption: ${it.message}") }
            while (isActive) {
                // Wakelock renouvelé à chaque cycle (borné) plutôt que tenu indéfiniment
                runCatching { wakeLock?.acquire(10 * 60_000L) }
                var interval = 15
                try {
                    runCycle()
                    val zone = app.database.facilityZoneDao().getFacilityZoneOnce()
                    interval = zone?.refreshIntervalFor(DateParsing.hourOf(System.currentTimeMillis())) ?: 15
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur cycle surveillance: ${e.message}")
                }
                // Battement persistant : à la relance, un trou trop long est signalé.
                app.preferences.lastMonitoringInterval = interval
                app.preferences.lastMonitoringBeat = System.currentTimeMillis()
                MonitoringHealth.update { it.copy(nextIntervalSeconds = interval) }
                delay(interval * 1000L)
            }
        }
    }

    /**
     * Au (re)démarrage de la boucle : si le dernier cycle connu est trop ancien (app tuée,
     * plantage, téléphone éteint), l'interruption est inscrite au journal (à traiter) et notifiée.
     */
    private suspend fun reportInterruption(app: SecuriResidentApp) {
        val prefs = app.preferences
        val crash = prefs.pendingCrash
        val now = System.currentTimeMillis()
        val gap = MonitoringGap.detect(prefs.lastMonitoringBeat, now, prefs.lastMonitoringInterval, crash)
        if (crash != null) prefs.pendingCrash = null
        val message = gap?.message()
            ?: crash?.let { "L'application a planté ($it) et la surveillance a redémarré automatiquement." }
            ?: return
        Log.w(TAG, message)
        app.database.alertEventDao().insertAlert(
            AlertEvent(residentId = -1, residentName = "Surveillance", alertType = AlertType.MONITORING_DEGRADED, details = message)
        )
        if (gap != null) app.soundAlertManager.notificationHelper.showWarning(null, AlertType.MONITORING_DEGRADED, message)
    }

    private suspend fun runCycle() {
        if (!cycleMutex.tryLock()) return
        try {
            doMonitoringCycle()
        } finally {
            cycleMutex.unlock()
        }
    }

    private suspend fun doMonitoringCycle() {
        val app = application as? SecuriResidentApp ?: return
        val now = System.currentTimeMillis()
        val zone = app.database.facilityZoneDao().getFacilityZoneOnce()
        val residents = app.database.residentDao().getAllResidentsOnce()
        val active = residents.filter { it.isTrackingActive }
        val night = zone?.isNight(DateParsing.hourOf(now)) == true
        zone?.name?.let { facilityName = it }

        if (zone == null || !zone.isZoneActive || active.isEmpty()) {
            MonitoringHealth.update {
                it.copy(serviceRunning = true, lastCycleAt = now, activeCount = active.size, okCount = 0,
                    errorCount = 0, consecutiveFailedCycles = 0, nightMode = night)
            }
            updateNotification(
                if (zone?.isZoneActive == false) "⏸ Zone désactivée — aucune alerte de sortie"
                else "En veille : aucun résident suivi", alert = zone?.isZoneActive == false
            )
            return
        }

        // Interrogation en parallèle (4 balises à la fois) pour garder un cycle court
        val semaphore = Semaphore(PARALLEL_SYNCS)
        val results = coroutineScopeAsync(active.map { r ->
            suspend { semaphore.withPermit { app.weenectRepository.syncResidentPosition(r).isSuccess } }
        })
        val ok = results.count { it }
        val errors = results.size - ok
        val allFailed = ok == 0 && results.isNotEmpty()

        MonitoringHealth.update {
            it.copy(
                serviceRunning = true,
                lastCycleAt = now,
                lastSuccessAt = if (ok > 0) now else it.lastSuccessAt,
                activeCount = active.size,
                okCount = ok,
                errorCount = errors,
                consecutiveFailedCycles = if (allFailed) it.consecutiveFailedCycles + 1 else 0,
                nightMode = night
            )
        }

        // Surveillance dégradée : avertissement sonore distinct (une fois, réarmé au retour)
        val health = MonitoringHealth.state.value
        if (health.consecutiveFailedCycles >= HealthSnapshot.DEGRADED_AFTER_CYCLES && !degradedNotified) {
            degradedNotified = true
            val msg = "Aucune balise n'a pu être interrogée depuis ${health.consecutiveFailedCycles} cycles. Vérifiez la connexion internet du téléphone."
            app.database.alertEventDao().insertAlert(
                AlertEvent(residentId = -1, residentName = "Toutes les balises", alertType = AlertType.MONITORING_DEGRADED, details = msg)
            )
            app.soundAlertManager.notificationHelper.showWarning(null, AlertType.MONITORING_DEGRADED, msg)
        } else if (!allFailed && degradedNotified) {
            degradedNotified = false
            app.soundAlertManager.notificationHelper.cancelWarning(-1L, AlertType.MONITORING_DEGRADED)
        }

        // Texte de la notification permanente = état réel
        val fresh = app.database.residentDao().getAllResidentsOnce()
        val staleMinutes = app.preferences.staleMinutes
        val statuses = fresh.map { ResidentStatusResolver.resolve(it, System.currentTimeMillis(), staleMinutes) }
        val out = statuses.count { it == ResidentStatus.OUT }
        val unknown = statuses.count { it == ResidentStatus.UNKNOWN || it == ResidentStatus.STALE }
        val text = MonitoringSummary.ongoingText(statuses, allFailed, night)
        updateNotification(text, alert = out > 0 || allFailed || unknown > 0)

        // Purge quotidienne du journal (> 180 jours)
        if (now - lastPurgeAt > 24 * 3_600_000L) {
            lastPurgeAt = now
            runCatching { app.database.alertEventDao().purgeOlderThan(now - 180L * 24 * 3_600_000L) }
        }
    }

    private suspend fun coroutineScopeAsync(tasks: List<suspend () -> Boolean>): List<Boolean> =
        kotlinx.coroutines.coroutineScope {
            tasks.map { task ->
                async {
                    try {
                        task()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync failed: ${e.message}")
                        false
                    }
                }
            }.awaitAll()
        }

    private fun buildOngoingNotification(statusText: String, alert: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_VIEW_STATUS)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITORING_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Surveillance — $facilityName")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .apply { if (alert) setColor(android.graphics.Color.RED) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String, alert: Boolean) {
        this.statusText = statusText
        statusAlert = alert
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification(statusText, alert))
    }

    private fun createMonitoringNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MONITORING_ID,
                "État du service de surveillance",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "État continu de la surveillance des balises en arrière-plan."
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        // Arrêt par le système (mémoire…) : START_STICKY le relance en principe, l'alarme en dernier recours.
        if (!stopRequested) MonitoringWatchdog.schedule(this, 60_000L)
        monitoringJob?.cancel()
        serviceScope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        MonitoringHealth.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ResidentMonitoring"
        private const val PARALLEL_SYNCS = 4
        const val CHANNEL_MONITORING_ID = "channel_resident_monitoring_status"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START = "fr.alerteresidents.service.ACTION_START"
        const val ACTION_STOP = "fr.alerteresidents.service.ACTION_STOP"
        const val ACTION_REFRESH_NOW = "fr.alerteresidents.service.ACTION_REFRESH_NOW"

        fun start(context: Context) = send(context, ACTION_START)

        /** Demande un cycle immédiat (actualisation manuelle) : une seule boucle de synchro pour toute l'app. */
        fun refreshNow(context: Context) = send(context, ACTION_REFRESH_NOW)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, ResidentMonitoringService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service: ${e.message}")
                MonitoringHealth.update { it.copy(serviceRunning = false) }
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ResidentMonitoringService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {}
        }
    }
}
