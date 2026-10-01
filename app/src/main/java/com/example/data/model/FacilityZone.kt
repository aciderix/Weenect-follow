package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "facility_zone")
data class FacilityZone(
    @PrimaryKey
    val id: Int = 1,
    val name: String = "MAS l'Épeau (Bouguenais)",
    val address: String = "1 rue Urbain le Verrier, 44340 Bouguenais",
    val centerLatitude: Double = 47.1787,
    val centerLongitude: Double = -1.6192,
    val radiusMeters: Double = 150.0,
    val isZoneActive: Boolean = true,
    val zoneType: String = "CIRCLE", // "CIRCLE" ou "POLYGON"
    val polygonPointsJson: String = "",
    val soundAlertsEnabled: Boolean = true,
    val vibrateAlertsEnabled: Boolean = true,
    val refreshIntervalSeconds: Int = 15
) {
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

    companion object {
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
    }
}
