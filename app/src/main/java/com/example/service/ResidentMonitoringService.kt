package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.SecuriResidentApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service d'arrière-plan permanent (Foreground Service).
 * Assure la surveillance et la détection d'alertes 24h/24 même si l'application est fermée ou le téléphone verrouillé.
 */
class ResidentMonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createMonitoringNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlerteResidents:MonitoringWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_NOW -> {
                serviceScope.launch { doMonitoringCycle() }
            }
            else -> {
                val notification = buildOngoingNotification("Surveillance active")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val hasLocationPermission = com.example.util.LocationHelper.hasLocationPermission(this)
                    val fgsType = if (hasLocationPermission) {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    }
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val hasLocationPermission = com.example.util.LocationHelper.hasLocationPermission(this)
                    if (hasLocationPermission) {
                        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startMonitoringLoop()
            }
        }
        return START_STICKY
    }

    private fun startMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            val app = application as? SecuriResidentApp ?: return@launch

            while (isActive) {
                try {
                    doMonitoringCycle()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e("ResidentMonitoring", "Erreur lors du cycle de surveillance: ${e.message}")
                }

                val zone = app.database.facilityZoneDao().getFacilityZoneOnce()
                val intervalSec = (zone?.refreshIntervalSeconds ?: 15).coerceAtLeast(10)
                try {
                    delay(intervalSec * 1000L)
                } catch (e: CancellationException) {
                    break
                }
            }
        }
    }

    private suspend fun doMonitoringCycle() {
        val app = application as? SecuriResidentApp ?: return
        val zone = app.database.facilityZoneDao().getFacilityZoneOnce()
        val residents = app.database.residentDao().getAllResidentsOnce()

        val activeResidents = residents.filter { it.isTrackingActive }

        // Si la zone est inactive ou aucun résident actif, on met à jour la notification
        if (zone == null || !zone.isZoneActive || activeResidents.isEmpty()) {
            updateNotification("En veille (${activeResidents.size}/${residents.size} résident(s) actifs)")
            return
        }

        // Acquisition temporaire du WakeLock pour garantir que le CPU ne s'endort pas pendant le calcul GPS
        wakeLock?.acquire(6000L)
        try {
            var outsideCount = 0
            for (resident in activeResidents) {
                try {
                    val result = app.weenectRepository.syncResidentPosition(resident)
                    if (result.isSuccess) {
                        val updated = result.getOrNull()
                        if (updated != null && !updated.isInZone) {
                            outsideCount++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ResidentMonitoring", "Sync failed for ${resident.name}: ${e.message}")
                }
            }

            // Si plus aucun résident n'est dehors mais que l'alarme sonnait, couper la sonnerie automatiquement
            if (outsideCount == 0 && app.soundAlertManager.isAlarmPlaying) {
                app.soundAlertManager.stopAlarm()
            }

            val statusText = if (outsideCount > 0) {
                "🚨 ALERTE : $outsideCount résident(s) hors de la zone de sécurité !"
            } else {
                "🟢 ${activeResidents.size} résidents suivis en sécurité - ${zone.name}"
            }
            updateNotification(statusText)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun buildOngoingNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(resources, R.drawable.app_logo)
                ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_MONITORING_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Surveillance active - MAS l'Épeau")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification(statusText))
    }

    private fun createMonitoringNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MONITORING_ID,
                "État du Service de Surveillance",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Affiche l'état continu de la surveillance des balises des résidents en arrière-plan."
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_MONITORING_ID = "channel_resident_monitoring_status"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_REFRESH_NOW = "com.example.service.ACTION_REFRESH_NOW"

        fun start(context: Context) {
            val intent = Intent(context, ResidentMonitoringService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ResidentMonitoring", "Failed to start monitoring service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ResidentMonitoringService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
