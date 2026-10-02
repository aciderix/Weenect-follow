package fr.alerteresidents.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Redémarre automatiquement le service de surveillance des résidents au démarrage du téléphone
 * ou après une mise à jour de l'application.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i("BootReceiver", "Démarrage automatique de la surveillance après $action")
            ResidentMonitoringService.start(context)
        }
    }
}
