package fr.alerteresidents.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    /**
     * Vérifie si l'application possède au moins une permission de localisation.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    /**
     * Récupère la position géographique actuelle de l'appareil de manière sécurisée.
     * Ne lance jamais de SecurityException si la permission n'est pas accordée.
     */
    suspend fun getCurrentLocation(context: Context): Location? = suspendCancellableCoroutine { continuation ->
        if (!hasLocationPermission(context)) {
            Log.w("LocationHelper", "Location permission not granted. Cannot get location.")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        var isGpsEnabled = false
        var isNetworkEnabled = false
        try {
            isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.w("LocationHelper", "Error checking provider status: ${e.message}")
        }

        // 1. Essayer d'obtenir la dernière position connue immédiatement
        var bestLastKnown: Location? = null
        try {
            if (isGpsEnabled) {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) bestLastKnown = gpsLoc
            }
            if (isNetworkEnabled) {
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null && (bestLastKnown == null || netLoc.time > bestLastKnown.time)) {
                    bestLastKnown = netLoc
                }
            }
        } catch (e: SecurityException) {
            Log.e("LocationHelper", "SecurityException on getLastKnownLocation: ${e.message}")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        } catch (e: Exception) {
            Log.w("LocationHelper", "Exception on getLastKnownLocation: ${e.message}")
        }

        // Si la dernière position est très récente (< 2 minutes), on l'utilise directement
        if (bestLastKnown != null && (System.currentTimeMillis() - bestLastKnown.time) < 120000) {
            continuation.resume(bestLastKnown)
            return@suspendCancellableCoroutine
        }

        // 2. Sinon demander un fix frais
        val provider = when {
            isGpsEnabled -> LocationManager.GPS_PROVIDER
            isNetworkEnabled -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            continuation.resume(bestLastKnown)
            return@suspendCancellableCoroutine
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    locationManager.removeUpdates(this)
                } catch (_: Exception) {}

                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationHelper", "SecurityException on requestLocationUpdates: ${e.message}")
            if (continuation.isActive) {
                continuation.resume(bestLastKnown)
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error requesting location: ${e.message}")
            if (continuation.isActive) {
                continuation.resume(bestLastKnown)
            }
        }

        continuation.invokeOnCancellation {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {}
        }
    }
}
