package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.Resident

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createAlertChannel()
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "🚨 Alertes Critiques Sortie de Zone",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Bandeau flottant prioritaire et alerte plein écran lors d'une sortie de zone"
                // Le son d'alarme continu 100% est géré exclusivement par SoundAlertManager pour éviter les doublons/échos
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 1000)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = android.graphics.Color.RED
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val activeAlertIds = mutableSetOf<Int>()

    fun showEmergencyNotification(resident: Resident, distanceMeters: Double) {
        // Intent d'ouverture d'urgence de l'application
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("EXTRA_RESIDENT_ID", resident.id)
            putExtra("EXTRA_ACTION", "VIEW_ALERT")
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            resident.id.toInt(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent de navigation Google Maps
        val lat = resident.lastLatitude ?: 0.0
        val lon = resident.lastLongitude ?: 0.0
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val navPendingIntent = PendingIntent.getActivity(
            context,
            (resident.id.toInt() + 10000),
            navIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent de coupure de sonnerie
        val silenceIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_ACTION", "SILENCE_ALARM")
            putExtra("EXTRA_RESIDENT_ID", resident.id)
        }
        val silencePendingIntent = PendingIntent.getActivity(
            context,
            (resident.id.toInt() + 20000),
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distStr = GeoUtils.formatDistance(distanceMeters)
        val appIconBitmap = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)
                ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🚨 ALERTE : ${resident.name} HORS ZONE !")
            .setContentText("Le résident est à $distStr du centre (${resident.roomNumber})")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("URGENCE : ${resident.name} a quitté le périmètre de sécurité de l'établissement.\nPosition actuelle : à $distStr du centre.\nAction requise par le personnel de garde.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 600, 200, 600, 200, 1000))
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(openAppPendingIntent, true) // Affiche le bandeau flottant (Heads-Up) ou réveil plein écran
            .setOnlyAlertOnce(true) // Empêche la notification de rejouer des alertes en boucle à chaque rafraîchissement de distance
            .setAutoCancel(false)
            .setColor(android.graphics.Color.RED)
            .setColorized(true)
            .setOngoing(true) // Reste visible jusqu'à coupure
            .addAction(
                android.R.drawable.ic_lock_silent_mode,
                "Couper sonnerie",
                silencePendingIntent
            )
            .addAction(
                android.R.drawable.ic_dialog_map,
                "Guidage GPS",
                navPendingIntent
            )

        if (appIconBitmap != null) {
            notificationBuilder.setLargeIcon(appIconBitmap)
        }

        val notification = notificationBuilder.build()
        val notifId = NOTIFICATION_ID_OFFSET + resident.id.toInt()
        activeAlertIds.add(notifId)
        notificationManager.notify(notifId, notification)
    }

    fun dismissEmergencyNotification(residentId: Long) {
        val notifId = NOTIFICATION_ID_OFFSET + residentId.toInt()
        activeAlertIds.remove(notifId)
        notificationManager.cancel(notifId)
    }

    fun dismissAllAlertNotifications() {
        activeAlertIds.forEach { id ->
            notificationManager.cancel(id)
        }
        activeAlertIds.clear()
    }

    companion object {
        const val CHANNEL_ID = "channel_resident_zone_alerts_v4"
        private const val NOTIFICATION_ID_OFFSET = 1000
    }
}
