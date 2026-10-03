package fr.alerteresidents.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Chien de garde : une alarme système relance la surveillance toutes les [INTERVAL_MS], même si
 * Android ou l'économiseur de batterie du fabricant a arrêté l'application. Si le service tourne
 * déjà, la relance ne fait rien de visible ; s'il avait été tué, il redémarre et l'interruption
 * est tracée dans le journal.
 */
object MonitoringWatchdog {
    private const val TAG = "MonitoringWatchdog"
    const val ACTION_WATCHDOG = "fr.alerteresidents.service.ACTION_WATCHDOG"
    const val INTERVAL_MS = 15 * 60_000L

    /** (Ré)arme la prochaine relance dans [delayMs] (remplace l'alarme précédente). */
    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context)
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            // Alarme exacte : réveille le téléphone en veille profonde et autorise le démarrage
            // du service au premier plan depuis l'arrière-plan (Android 12+).
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Alarme exacte refusée : ${e.message}")
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) }
        }
    }

    /** Les alarmes exactes sont autorisées (toujours avant Android 12, réglage utilisateur ensuite). */
    fun canScheduleExact(context: Context): Boolean =
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.let { canScheduleExact(it) } ?: false

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Reçoit l'alarme du chien de garde : réarme la suivante puis relance la surveillance. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        MonitoringWatchdog.schedule(context)
        ResidentMonitoringService.start(context)
    }
}
