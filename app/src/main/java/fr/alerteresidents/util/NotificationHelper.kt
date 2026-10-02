package fr.alerteresidents.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import fr.alerteresidents.MainActivity
import fr.alerteresidents.R
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val lock = Any()
    /** residentId -> ligne affichée dans la notification de synthèse */
    private val activeAlerts = linkedMapOf<Long, String>()

    private val logo: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_logo) }.getOrNull()
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val alerts = NotificationChannel(CHANNEL_ID, "🚨 Alertes critiques sortie de zone", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerte plein écran lors d'une sortie de zone"
            // Le son continu est géré par SoundAlertManager (canal ALARM)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 1000)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableLights(true)
            lightColor = android.graphics.Color.RED
            setBypassDnd(true)
        }
        val warnings = NotificationChannel(CHANNEL_WARNINGS, "⚠️ Avertissements (balises, batterie, connexion)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Balise muette, batterie faible, surveillance dégradée"
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(CHANNEL_INFO, "Informations (retours, fins de sortie)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Retour d'un résident dans la zone, fin de sortie accompagnée"
        }
        notificationManager.createNotificationChannels(listOf(alerts, warnings, info))
        // Anciens canaux des versions précédentes
        listOf("channel_resident_zone_alerts").forEach { runCatching { notificationManager.deleteNotificationChannel(it) } }
    }

    /** Android 14+ : l'affichage plein écran nécessite une autorisation spéciale. */
    fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) notificationManager.canUseFullScreenIntent() else true

    fun areNotificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()

    private fun activityIntent(action: String, residentId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
            putExtra(MainActivity.EXTRA_RESIDENT_ID, residentId)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun requestCode(residentId: Long, slot: Int) = (residentId.hashCode() and 0x0FFFFFFF) * 8 + slot

    fun showEmergencyNotification(resident: Resident, zone: FacilityZone, isDrill: Boolean, isReminder: Boolean) {
        val distStr = GeoUtils.formatDistance(resident.distanceFromCenterMeters)
        val distText = if (zone.isPolygon) "à $distStr de la limite de la zone" else "à $distStr du centre"
        val prefix = when {
            isDrill -> "EXERCICE — "
            isReminder -> "RAPPEL — "
            else -> ""
        }
        val room = resident.roomNumber.ifBlank { "chambre non renseignée" }
        val title = "${prefix}🚨 ${resident.name} HORS ZONE"
        val text = if (resident.hasPosition) "$distText • $room" else room

        val open = activityIntent(MainActivity.ACTION_VIEW_ALERT, resident.id, requestCode(resident.id, 0))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$text\n" + if (isReminder) "Sortie toujours en cours, personne n'a encore pris l'alerte en charge."
                    else "Action requise : qui s'en occupe ?"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOnlyAlertOnce(!isReminder)
            .setAutoCancel(false)
            .setOngoing(true)
            .setColor(android.graphics.Color.RED)
            .setColorized(true)
            .setGroup(GROUP_ALERTS)
            .setLargeIcon(PhotoStore.load(resident.photoUri) ?: logo)
            .addAction(
                android.R.drawable.ic_menu_myplaces, "Je m'en occupe",
                activityIntent(MainActivity.ACTION_HANDLE_ALERT, resident.id, requestCode(resident.id, 1))
            )
            .addAction(
                android.R.drawable.ic_lock_silent_mode, "Couper sonnerie",
                activityIntent(MainActivity.ACTION_SILENCE_ALARM, resident.id, requestCode(resident.id, 2))
            )
        val lat = resident.lastLatitude
        val lon = resident.lastLongitude
        if (lat != null && lon != null) {
            builder.addAction(
                android.R.drawable.ic_dialog_map, "Guider",
                PendingIntent.getActivity(
                    context, requestCode(resident.id, 3), MapsNavigator.webIntent(lat, lon),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        synchronized(lock) {
            activeAlerts[resident.id] = "${prefix}${resident.name} — ${if (resident.hasPosition) distStr else room}"
        }
        notificationManager.notify(alertId(resident.id), builder.build())
        updateSummary()
    }

    private fun updateSummary() {
        val lines = synchronized(lock) { activeAlerts.values.toList() }
        if (lines.size < 2) {
            notificationManager.cancel(SUMMARY_ID)
            return
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle("🚨 ${lines.size} résidents hors zone")
        lines.forEach { style.addLine(it) }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🚨 ${lines.size} résidents hors zone")
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(android.graphics.Color.RED)
            .setContentIntent(activityIntent(MainActivity.ACTION_VIEW_ALERT, -1L, SUMMARY_ID))
            .build()
        notificationManager.notify(SUMMARY_ID, summary)
    }

    fun dismissEmergencyNotification(residentId: Long) {
        synchronized(lock) { activeAlerts.remove(residentId) }
        notificationManager.cancel(alertId(residentId))
        updateSummary()
    }

    fun dismissAllAlertNotifications() {
        val ids = synchronized(lock) { activeAlerts.keys.toList().also { activeAlerts.clear() } }
        ids.forEach { notificationManager.cancel(alertId(it)) }
        notificationManager.cancel(SUMMARY_ID)
    }

    /** Avertissement non bloquant (batterie, balise muette, connexion), avec son de notification. */
    fun showWarning(resident: Resident?, type: String, message: String) {
        val title = "⚠️ ${AlertType.label(type)}" + (resident?.let { " — ${it.name}" } ?: "")
        val notification = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(MainActivity.ACTION_VIEW_STATUS, resident?.id ?: -1L, requestCode(resident?.id ?: -1L, 4)))
            .build()
        notificationManager.notify(warningId(resident?.id ?: -1L, type), notification)
    }

    fun showInfo(resident: Resident, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(MainActivity.ACTION_VIEW_RESIDENT, resident.id, requestCode(resident.id, 5)))
            .build()
        notificationManager.notify(INFO_OFFSET + (resident.id.hashCode() and 0xFFFF), notification)
    }

    fun cancelWarning(residentId: Long, type: String) = notificationManager.cancel(warningId(residentId, type))

    private fun alertId(residentId: Long) = ALERT_OFFSET + (residentId.hashCode() and 0xFFFF)
    private fun warningId(residentId: Long, type: String) = WARNING_OFFSET + ((residentId.hashCode() * 31 + type.hashCode()) and 0xFFFF)

    companion object {
        const val CHANNEL_ID = "channel_resident_zone_alerts_v4"
        const val CHANNEL_WARNINGS = "channel_warnings_v1"
        const val CHANNEL_INFO = "channel_info_v1"
        private const val GROUP_ALERTS = "group_zone_alerts"
        private const val SUMMARY_ID = 999
        private const val ALERT_OFFSET = 100_000
        private const val WARNING_OFFSET = 200_000
        private const val INFO_OFFSET = 300_000
    }
}
