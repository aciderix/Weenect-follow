package fr.alerteresidents.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

enum class MapTileStyle(val displayName: String) {
    OPEN_STREET_MAP("Plan standard"),
    OSM_FR("Plan France"),
    SATELLITE("Vue Satellite")
}

class MapTileProvider(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cacheDir = File(context.cacheDir, "osm_tiles_v3").apply { mkdirs() }
    private val okHttpClient = HttpClients.base.newBuilder()
        .cache(Cache(cacheDir, 60L * 1024L * 1024L)) // 60MB disk cache
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // In-memory cache for fast Compose canvas drawing
    private val memoryCache = object : LruCache<String, ImageBitmap>(160) {}

    // Observable map for Compose recomposition
    val loadedTiles = mutableStateMapOf<String, ImageBitmap>()

    private val activeLoads = ConcurrentHashMap<String, Boolean>()

    init {
        // Clear any old legacy cache directories (e.g. cartodb cache)
        scope.launch {
            try {
                val oldDir = File(context.cacheDir, "osm_tiles")
                if (oldDir.exists()) {
                    oldDir.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    fun getTile(zoom: Int, x: Int, y: Int, style: MapTileStyle): ImageBitmap? {
        val maxTile = (1 shl zoom) - 1
        if (x < 0 || x > maxTile || y < 0 || y > maxTile) return null

        val key = "${style.name}_${zoom}_${x}_${y}"
        memoryCache.get(key)?.let { return it }

        // Start async download if not already in flight
        if (activeLoads.putIfAbsent(key, true) == null) {
            scope.launch {
                try {
                    val url = when (style) {
                        MapTileStyle.OPEN_STREET_MAP ->
                            "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                        MapTileStyle.OSM_FR ->
                            "https://a.tile.openstreetmap.fr/osmfr/$zoom/$x/$y.png"
                        MapTileStyle.SATELLITE ->
                            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$y/$x"
                    }

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", HttpClients.USER_AGENT)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    val imageBitmap = bitmap.asImageBitmap()
                                    memoryCache.put(key, imageBitmap)
                                    loadedTiles[key] = imageBitmap
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore, retry on pan/zoom
                } finally {
                    activeLoads.remove(key)
                }
            }
        }

        return loadedTiles[key]
    }

    companion object {
        const val TILE_SIZE = 256.0

        /**
         * Convertit des coordonnées GPS (lat, lon) en coordonnées mondiales en pixels au niveau de zoom donné.
         */
        fun latLonToWorld(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
            val scale = TILE_SIZE * (1 shl zoom)
            val clampedLon = lon.coerceIn(-180.0, 180.0)
            val worldX = (clampedLon + 180.0) / 360.0 * scale

            val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
            val sinLat = sin(Math.toRadians(clampedLat))
            val worldY = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * scale

            return Pair(worldX, worldY)
        }

        /**
         * Calcule la résolution au sol en mètres par pixel au niveau de zoom et latitude donnés.
         */
        fun metersPerPixel(lat: Double, zoom: Int): Double {
            return 156543.03392 * cos(Math.toRadians(lat)) / (1 shl zoom)
        }
    }
}
