package fr.alerteresidents.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Guidage piéton vers une position : appli Google Maps si présente, sinon toute appli de cartes, sinon navigateur. */
object MapsNavigator {
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun navigate(context: Context, lat: Double, lon: Double) {
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w")).setPackage(MAPS_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")),
            webIntent(lat, lon)
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(context, "Aucune application de cartes disponible", Toast.LENGTH_LONG).show()
    }

    /** Intent utilisable dans une notification (ouvert par Google Maps via les liens d'application). */
    fun webIntent(lat: Double, lon: Double): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=walking&dir_action=navigate")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
