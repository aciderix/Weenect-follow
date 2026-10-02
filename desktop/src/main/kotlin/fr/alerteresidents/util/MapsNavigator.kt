package fr.alerteresidents.util

import java.awt.Desktop
import java.net.URI

/** Sur PC : itinéraire piéton Google Maps dans le navigateur. */
object MapsNavigator {
    fun navigate(lat: Double, lon: Double) {
        runCatching {
            Desktop.getDesktop().browse(URI("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=walking"))
        }
    }
}
