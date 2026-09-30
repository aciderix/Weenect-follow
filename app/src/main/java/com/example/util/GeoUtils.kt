package com.example.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calcule la distance en mètres entre deux coordonnées GPS via la formule de Haversine.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calcule le cap (azimut) en degrés (0..360) depuis le point 1 vers le point 2.
     */
    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * Convertit un cap en direction cardinale en français.
     */
    fun bearingToCardinal(bearing: Double): String {
        val directions = arrayOf(
            "Nord", "Nord-Est", "Est", "Sud-Est",
            "Sud", "Sud-Ouest", "Ouest", "Nord-Ouest"
        )
        val index = (((bearing + 22.5) % 360) / 45).toInt()
        return directions[index.coerceIn(0, 7)]
    }

    /**
     * Formate une distance lisiblement pour le personnel.
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()} m"
        } else {
            String.format("%.1f km", meters / 1000.0)
        }
    }

    /**
     * Formate un timestamp relatif en français.
     */
    fun formatTimeAgo(timestampMs: Long?): String {
        if (timestampMs == null || timestampMs <= 0) return "Jamais"
        val diff = System.currentTimeMillis() - timestampMs
        val seconds = diff / 1000
        return when {
            seconds < 10 -> "À l'instant"
            seconds < 60 -> "Il y a ${seconds}s"
            seconds < 3600 -> "Il y a ${seconds / 60} min"
            seconds < 86400 -> "Il y a ${seconds / 3600} h"
            else -> "Il y a ${seconds / 86400} j"
        }
    }
}
