package fr.alerteresidents.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

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
        return try {
            pointsAdapter.fromJson(polygonPointsJson).orEmpty().map { it.lat to it.lon }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getExtraZones(): List<ExtraZone> {
        if (extraZonesJson.isBlank()) return emptyList()
        return try {
            extrasAdapter.fromJson(extraZonesJson).orEmpty().map {
                ExtraZone(it.name, it.lat, it.lon, it.radius, it.night)
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

        private val moshi = Moshi.Builder().build()
        private val pointsAdapter = moshi.adapter<List<LatLonJson>>(Types.newParameterizedType(List::class.java, LatLonJson::class.java))
        private val extrasAdapter = moshi.adapter<List<ExtraZoneJson>>(Types.newParameterizedType(List::class.java, ExtraZoneJson::class.java))

        fun encodePolygonPoints(points: List<Pair<Double, Double>>): String =
            pointsAdapter.toJson(points.map { (lat, lon) -> LatLonJson(lat, lon) })

        fun encodeExtraZones(zones: List<ExtraZone>): String {
            if (zones.isEmpty()) return ""
            return extrasAdapter.toJson(zones.map { ExtraZoneJson(it.name, it.latitude, it.longitude, it.radiusMeters, it.allowedAtNight) })
        }
    }
}

/** Format JSON historique des points du polygone : [{"lat":…,"lon":…}] */
@JsonClass(generateAdapter = true)
internal data class LatLonJson(val lat: Double, val lon: Double)

/** Format JSON des zones annexes : [{"name","lat","lon","radius","night"}] */
@JsonClass(generateAdapter = true)
internal data class ExtraZoneJson(
    val name: String = "Zone annexe",
    val lat: Double,
    val lon: Double,
    val radius: Double = 50.0,
    val night: Boolean = false
)
