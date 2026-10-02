package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.remote.WeenectRepository
import com.example.util.SoundAlertManager

class SecuriResidentApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var soundAlertManager: SoundAlertManager
        private set

    lateinit var weenectRepository: WeenectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        soundAlertManager = SoundAlertManager(this)

        weenectRepository = WeenectRepository(
            residentDao = database.residentDao(),
            facilityZoneDao = database.facilityZoneDao(),
            alertEventDao = database.alertEventDao(),
            onZoneExitDetected = { resident, distance ->
                soundAlertManager.playZoneExitAlarm(
                    resident = resident,
                    distanceMeters = distance,
                    soundEnabled = true,
                    vibrateEnabled = true,
                    forceMaxVolume = true
                )
            },
            onZoneEnterDetected = { resident ->
                soundAlertManager.stopAlarm(resident.id)
            }
        )

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Alertes de Sécurité Résidents",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifie immédiatement le personnel en cas de sortie de zone d'un résident."
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ALERTS_ID = "channel_resident_zone_alerts"
    }
}
