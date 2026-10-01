package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? = suspendCancellableCoroutine { continuation ->
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        // 1. Essayer d'obtenir la dernière position connue immédiatement
        var bestLastKnown: Location? = null
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

        // Si la dernière position est très récente (< 2 minutes), on l'utilise
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
                locationManager.removeUpdates(this)
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
