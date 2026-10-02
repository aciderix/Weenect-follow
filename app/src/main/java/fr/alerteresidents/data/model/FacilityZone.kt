package fr.alerteresidents.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/** Zone annexe autorisée (jardin, parking…), en plus de la zone principale. */
data class ExtraZone(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    /** Si false, la zone n'est autorisée qu'en journée (hors plage de nuit). */
    val allowedAtNight: Boolean = false
)

@Entity(tableName = "facility_zone")
data class FacilityZone(
    @PrimaryKey
    val id: Int = 1,
    val name: String = DEFAULT_NAME,
    val address: String = "",
    val centerLatitude: Double = 47.1787,
    val centerLongitude: Double = -1.6192,
    val radiusMeters: Double = 150.0,
    val isZoneActive: Boolean = true,
    val zoneType: String = "CIRCLE", // "CIRCLE" ou "POLYGON"
    val polygonPointsJson: String = "",
    val soundAlertsEnabled: Boolean = true,
    val vibrateAlertsEnabled: Boolean = true,
    val refreshIntervalSeconds: Int = 15,
    // --- v4 ---
    val extraZonesJson: String = "",
    val nightModeEnabled: Boolean = false,
    val nightStartHour: Int = 21,
    val nightEndHour: Int = 7,
    val nightRefreshIntervalSeconds: Int = 10
) {
    val isPolygon: Boolean get() = zoneType == "POLYGON" && getPolygonPoints().size >= 3

    fun getPolygonPoints(): List<Pair<Double, Double>> {
        if (polygonPointsJson.isBlank()) return emptyList()
        val list = mutableListOf<Pair<Double, Double>>()
        try {
            val jsonArray = JSONArray(polygonPointsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val lat = obj.optDouble("lat")
                val lon = obj.optDouble("lon")
                list.add(Pair(lat, lon))
            }
        } catch (_: Exception) {}
        return list
    }

    fun getExtraZones(): List<ExtraZone> {
        if (extraZonesJson.isBlank()) return emptyList()
        return try {
            val array = JSONArray(extraZonesJson)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ExtraZone(
                    name = o.optString("name", "Zone annexe"),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon"),
                    radiusMeters = o.optDouble("radius", 50.0),
                    allowedAtNight = o.optBoolean("night", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Vrai si [hour] (0-23) tombe dans la plage de nuit configurée. */
    fun isNight(hour: Int): Boolean {
        if (!nightModeEnabled) return false
        return if (nightStartHour <= nightEndHour) hour in nightStartHour until nightEndHour
        else hour >= nightStartHour || hour < nightEndHour
    }

    fun refreshIntervalFor(hour: Int): Int =
        (if (isNight(hour)) nightRefreshIntervalSeconds else refreshIntervalSeconds).coerceAtLeast(10)

    companion object {
        const val DEFAULT_NAME = "Mon établissement"

        fun encodePolygonPoints(points: List<Pair<Double, Double>>): String {
            val array = JSONArray()
            points.forEach { (lat, lon) ->
                val obj = JSONObject()
                obj.put("lat", lat)
                obj.put("lon", lon)
                array.put(obj)
            }
            return array.toString()
        }

        fun encodeExtraZones(zones: List<ExtraZone>): String {
            if (zones.isEmpty()) return ""
            val array = JSONArray()
            zones.forEach { z ->
                array.put(
                    JSONObject()
                        .put("name", z.name)
                        .put("lat", z.latitude)
                        .put("lon", z.longitude)
                        .put("radius", z.radiusMeters)
                        .put("night", z.allowedAtNight)
                )
            }
            return array.toString()
        }
    }
}
