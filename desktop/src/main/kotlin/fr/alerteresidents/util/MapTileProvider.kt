package fr.alerteresidents.util

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import fr.alerteresidents.desktop.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

enum class MapTileStyle(val displayName: String) {
    OPEN_STREET_MAP("Plan standard"),
    OSM_FR("Plan France"),
    SATELLITE("Vue Satellite")
}

/** Tuiles de carte (OpenStreetMap / satellite) avec cache disque de 60 Mo. */
class MapTileProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = HttpClients.base.newBuilder()
        .cache(Cache(File(Platform.dataDir, "cache-cartes"), 60L * 1024L * 1024L))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }
    val loadedTiles = mutableStateMapOf<String, ImageBitmap>()
    private val activeLoads = ConcurrentHashMap<String, Boolean>()

    fun getTile(zoom: Int, x: Int, y: Int, style: MapTileStyle): ImageBitmap? {
        val maxTile = (1 shl zoom) - 1
        if (x < 0 || x > maxTile || y < 0 || y > maxTile) return null
        val key = "${style.name}_${zoom}_${x}_$y"
        synchronized(memoryCache) { memoryCache[key] }?.let { return it }
        if (activeLoads.putIfAbsent(key, true) == null) {
            scope.launch {
                try {
                    val url = when (style) {
                        MapTileStyle.OPEN_STREET_MAP -> "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                        MapTileStyle.OSM_FR -> "https://a.tile.openstreetmap.fr/osmfr/$zoom/$x/$y.png"
                        MapTileStyle.SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$y/$x"
                    }
                    okHttpClient.newCall(Request.Builder().url(url).header("User-Agent", HttpClients.userAgent).build()).execute().use { r ->
                        val bytes = r.body?.bytes()
                        if (r.isSuccessful && bytes != null && bytes.isNotEmpty()) {
                            val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
                            synchronized(memoryCache) { memoryCache[key] = bitmap }
                            loadedTiles[key] = bitmap
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    activeLoads.remove(key)
                }
            }
        }
        return loadedTiles[key]
    }

    companion object {
        const val TILE_SIZE = 256.0

        fun latLonToWorld(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
            val scale = TILE_SIZE * (1 shl zoom)
            val worldX = (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * scale
            val sinLat = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
            val worldY = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * scale
            return Pair(worldX, worldY)
        }

        /** Inverse de [latLonToWorld] : coordonnées monde (pixels) → latitude / longitude. */
        fun worldToLatLon(worldX: Double, worldY: Double, zoom: Int): Pair<Double, Double> {
            val scale = TILE_SIZE * (1 shl zoom)
            val lon = worldX / scale * 360.0 - 180.0
            val n = PI - 2.0 * PI * worldY / scale
            val lat = Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
            return Pair(lat, lon)
        }

        fun metersPerPixel(lat: Double, zoom: Int): Double = 156543.03392 * cos(Math.toRadians(lat)) / (1 shl zoom)
    }
}
