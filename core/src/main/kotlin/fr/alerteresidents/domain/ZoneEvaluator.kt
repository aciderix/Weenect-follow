package fr.alerteresidents.domain

import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.util.GeoUtils

data class ZoneEvaluation(
    /** Position dans une zone autorisée (principale ou annexe autorisée à cette heure). */
    val inside: Boolean,
    /** Distance affichée : au centre (zone circulaire) ou à la limite (zone polygonale). */
    val distanceMeters: Double,
    /** Distance au-delà de la limite de la zone autorisée la plus proche (0 si dedans). */
    val metersBeyondBoundary: Double,
    /** Dehors même en tenant compte de l'imprécision du fix : sortie confirmée immédiatement. */
    val clearlyOutside: Boolean
)

object ZoneEvaluator {

    fun evaluate(zone: FacilityZone, lat: Double, lon: Double, accuracyMeters: Int?, hour: Int): ZoneEvaluation {
        if (!zone.isZoneActive) return ZoneEvaluation(true, 0.0, 0.0, false)

        val (insideMain, displayDistance, beyondMain) = if (zone.isPolygon) {
            val polygon = zone.getPolygonPoints()
            val inside = GeoUtils.isPointInPolygon(lat, lon, polygon)
            val toBorder = GeoUtils.distanceToPolygonBoundaryMeters(lat, lon, polygon)
            Triple(inside, if (inside) 0.0 else toBorder, if (inside) 0.0 else toBorder)
        } else {
            val d = GeoUtils.calculateDistanceMeters(lat, lon, zone.centerLatitude, zone.centerLongitude)
            Triple(d <= zone.radiusMeters, d, (d - zone.radiusMeters).coerceAtLeast(0.0))
        }

        val night = zone.isNight(hour)
        var beyond = beyondMain
        var inside = insideMain
        for (extra in zone.getExtraZones()) {
            if (night && !extra.allowedAtNight) continue
            val d = GeoUtils.calculateDistanceMeters(lat, lon, extra.latitude, extra.longitude)
            if (d <= extra.radiusMeters) inside = true
            beyond = minOf(beyond, (d - extra.radiusMeters).coerceAtLeast(0.0))
        }
        if (inside) beyond = 0.0

        val margin = (accuracyMeters ?: 0).coerceAtLeast(0).toDouble()
        return ZoneEvaluation(
            inside = inside,
            distanceMeters = if (inside && zone.isPolygon) 0.0 else displayDistance,
            metersBeyondBoundary = beyond,
            clearlyOutside = !inside && beyond > margin
        )
    }
}
