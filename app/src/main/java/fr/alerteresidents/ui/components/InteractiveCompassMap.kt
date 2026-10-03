package fr.alerteresidents.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.remote.HistoryPoint
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeGreen
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.util.MapTileProvider
import fr.alerteresidents.util.MapTileStyle
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Résident affichable sur la carte (uniquement s'il a une position réelle). */
data class MapMarker(val resident: Resident, val status: ResidentStatus)

private const val MIN_ZOOM = 12
private const val MAX_ZOOM = 19

@Composable
fun InteractiveCompassMap(
    facilityZone: FacilityZone,
    markers: List<MapMarker>,
    selectedResidentId: Long?,
    history: List<HistoryPoint>?,
    onSelectResident: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tileProvider = remember { MapTileProvider(context) }
    val c = AppStatusColors

    var currentStyle by remember { mutableStateOf(MapTileStyle.OPEN_STREET_MAP) }
    var zoomLevel by remember { mutableIntStateOf(16) }
    var subZoom by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var alertCycleIndex by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        0.85f, 0.05f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulseAlpha"
    )
    val pulseRadius by infiniteTransition.animateFloat(
        20f, 54f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulseRadius"
    )

    val centerLat = facilityZone.centerLatitude
    val centerLon = facilityZone.centerLongitude

    /** Centre la vue sur une position au zoom courant. */
    fun centerOn(lat: Double, lon: Double, zoom: Int = zoomLevel) {
        zoomLevel = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        subZoom = 1f
        val (cx, cy) = MapTileProvider.latLonToWorld(centerLat, centerLon, zoomLevel)
        val (tx, ty) = MapTileProvider.latLonToWorld(lat, lon, zoomLevel)
        panOffsetX = -(tx - cx).toFloat()
        panOffsetY = -(ty - cy).toFloat()
    }

    /** Ajuste zoom et position pour voir l'établissement et tous les résidents. */
    fun fitAll() {
        val points = markers.map { it.resident.lastLatitude!! to it.resident.lastLongitude!! } + (centerLat to centerLon)
        val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
        var zoom = MAX_ZOOM
        val w = (viewport.width * 0.75).coerceAtLeast(200.0)
        val h = (viewport.height * 0.6).coerceAtLeast(200.0)
        while (zoom > MIN_ZOOM) {
            val (x1, y1) = MapTileProvider.latLonToWorld(maxLat, minLon, zoom)
            val (x2, y2) = MapTileProvider.latLonToWorld(minLat, maxLon, zoom)
            if (x2 - x1 <= w && y2 - y1 <= h) break
            zoom--
        }
        centerOn((minLat + maxLat) / 2, (minLon + maxLon) / 2, minOf(zoom, 18))
    }

    // Centre automatiquement sur le résident sélectionné
    LaunchedEffect(selectedResidentId) {
        markers.find { it.resident.id == selectedResidentId }?.let { centerOn(it.resident.lastLatitude!!, it.resident.lastLongitude!!) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("interactive_compass_map")
            .pointerInput(zoomLevel) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    panOffsetX += pan.x
                    panOffsetY += pan.y
                    val newSubZoom = subZoom * zoomChange
                    if (newSubZoom > 1.7f && zoomLevel < MAX_ZOOM) {
                        zoomLevel += 1; subZoom = 1.0f; panOffsetX *= 2f; panOffsetY *= 2f
                    } else if (newSubZoom < 0.65f && zoomLevel > MIN_ZOOM) {
                        zoomLevel -= 1; subZoom = 1.0f; panOffsetX *= 0.5f; panOffsetY *= 0.5f
                    } else {
                        subZoom = newSubZoom.coerceIn(0.7f, 1.6f)
                    }
                }
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        val screenCenterX = width / 2f + panOffsetX
        val screenCenterY = height / 2f + panOffsetY
        val tileCacheVersion = tileProvider.loadedTiles.size
        val (centerWorldX, centerWorldY) = MapTileProvider.latLonToWorld(centerLat, centerLon, zoomLevel)

        fun toScreen(lat: Double, lon: Double): Offset {
            val (wx, wy) = MapTileProvider.latLonToWorld(lat, lon, zoomLevel)
            return Offset(
                screenCenterX + ((wx - centerWorldX) * subZoom).toFloat(),
                screenCenterY + ((wy - centerWorldY) * subZoom).toFloat()
            )
        }

        // Regroupement des marqueurs proches (sauf résidents hors zone, toujours visibles seuls)
        val clusterRadiusPx = with(density) { 40.dp.toPx() }
        val positioned = markers.map { it to toScreen(it.resident.lastLatitude!!, it.resident.lastLongitude!!) }
        val clusters = mutableListOf<MutableList<Pair<MapMarker, Offset>>>()
        for (p in positioned.sortedBy { it.first.status.priority }) {
            val alone = p.first.status == ResidentStatus.OUT || p.first.resident.id == selectedResidentId
            val target = if (alone) null else clusters.firstOrNull { cl ->
                cl.first().first.status != ResidentStatus.OUT && cl.first().first.resident.id != selectedResidentId &&
                    hypot(cl.first().second.x - p.second.x, cl.first().second.y - p.second.y) < clusterRadiusPx
            }
            if (target != null) target.add(p) else clusters.add(mutableListOf(p))
        }

        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val v = tileCacheVersion
            val tileSize = (MapTileProvider.TILE_SIZE * subZoom).toFloat()
            val minWorldX = centerWorldX - (screenCenterX / subZoom)
            val maxWorldX = centerWorldX + ((width - screenCenterX) / subZoom)
            val minWorldY = centerWorldY - (screenCenterY / subZoom)
            val maxWorldY = centerWorldY + ((height - screenCenterY) / subZoom)
            val maxTileIndex = (1 shl zoomLevel) - 1
            for (tx in floor(minWorldX / MapTileProvider.TILE_SIZE).toInt()..floor(maxWorldX / MapTileProvider.TILE_SIZE).toInt()) {
                if (tx < 0 || tx > maxTileIndex) continue
                for (ty in floor(minWorldY / MapTileProvider.TILE_SIZE).toInt()..floor(maxWorldY / MapTileProvider.TILE_SIZE).toInt()) {
                    if (ty < 0 || ty > maxTileIndex) continue
                    val drawX = screenCenterX + ((tx * MapTileProvider.TILE_SIZE - centerWorldX) * subZoom).toFloat()
                    val drawY = screenCenterY + ((ty * MapTileProvider.TILE_SIZE - centerWorldY) * subZoom).toFloat()
                    val tile = tileProvider.getTile(zoomLevel, tx, ty, currentStyle)
                    if (tile != null) {
                        drawImage(tile, dstOffset = IntOffset(drawX.roundToInt(), drawY.roundToInt()),
                            dstSize = IntSize(tileSize.roundToInt(), tileSize.roundToInt()))
                    } else {
                        drawRect(if (currentStyle == MapTileStyle.SATELLITE) Color(0xFF1E262C) else Color(0xFFE2E8F0), Offset(drawX, drawY), Size(tileSize, tileSize))
                    }
                }
            }

            val mpp = MapTileProvider.metersPerPixel(centerLat, zoomLevel) / subZoom
            // Zone principale
            if (facilityZone.isPolygon) {
                val pts = facilityZone.getPolygonPoints().map { toScreen(it.first, it.second) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, SafeGreen.copy(alpha = 0.22f), style = Fill)
                drawPath(path, SafeGreen, style = Stroke(width = 3.5.dp.toPx()))
            } else {
                val r = (facilityZone.radiusMeters / mpp).toFloat()
                drawCircle(SafeGreen.copy(alpha = 0.18f), r, Offset(screenCenterX, screenCenterY))
                drawCircle(SafeGreen, r, Offset(screenCenterX, screenCenterY), style = Stroke(width = 3.dp.toPx()))
            }
            // Zones annexes
            facilityZone.getExtraZones().forEach { z ->
                val center = toScreen(z.latitude, z.longitude)
                val r = (z.radiusMeters / mpp).toFloat()
                val col = Color(0xFF0EA5E9)
                drawCircle(col.copy(alpha = 0.15f), r, center)
                drawCircle(col, r, center, style = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            }
            // Bâtiment
            drawCircle(SafeNavy, 12.dp.toPx(), Offset(screenCenterX, screenCenterY))
            drawCircle(Color.White, 4.dp.toPx(), Offset(screenCenterX, screenCenterY))

            // Trajet du résident sélectionné
            history?.takeIf { it.size >= 2 }?.let { pts ->
                val screen = pts.map { toScreen(it.latitude, it.longitude) }
                val path = Path().apply { moveTo(screen[0].x, screen[0].y); screen.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, Color(0xFF7C3AED), style = Stroke(width = 4.dp.toPx()))
                screen.forEachIndexed { i, p ->
                    drawCircle(Color.White, 4.dp.toPx(), p)
                    drawCircle(Color(0xFF7C3AED), if (i == 0) 5.dp.toPx() else 3.dp.toPx(), p)
                }
            }

            // Résidents hors zone : trait depuis l'établissement + onde
            positioned.filter { it.first.status == ResidentStatus.OUT }.forEach { (_, p) ->
                drawLine(c.danger.copy(alpha = 0.85f), Offset(screenCenterX, screenCenterY), p, 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                drawCircle(c.danger.copy(alpha = pulseAlpha), pulseRadius.dp.toPx(), p)
            }
        }

        // Marqueurs (composables : photo ou initiales, cliquables)
        val markerSize = 36.dp
        val half = with(density) { (markerSize / 2).roundToPx() }
        clusters.forEach { cl ->
            if (cl.size == 1) {
                val (m, p) = cl.first()
                val style = statusStyle(m.status)
                val selected = m.resident.id == selectedResidentId
                val showLabel = selected || m.status == ResidentStatus.OUT || zoomLevel >= 18
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .offset { IntOffset(p.x.roundToInt() - half * 3, p.y.roundToInt() - half) }
                        .width(markerSize * 3)
                        .clickable { onSelectResident(m.resident.id) }
                        .testTag("map_marker_${m.resident.id}")
                ) {
                    ResidentAvatar(
                        m.resident, if (selected) 44.dp else markerSize, ringColor = style.accent,
                        modifier = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
                    )
                    if (showLabel) {
                        Surface(color = if (m.status == ResidentStatus.OUT) style.solid else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp, modifier = Modifier.padding(top = 2.dp)) {
                            Text(
                                m.resident.name.substringBefore(' ').take(14),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                color = if (m.status == ResidentStatus.OUT) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            } else {
                val p = Offset(cl.map { it.second.x }.average().toFloat(), cl.map { it.second.y }.average().toFloat())
                val worst = cl.minOf { it.first.status.priority }
                val color = statusStyle(ResidentStatus.entries.first { it.priority == worst }).solid
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - half) }
                        .size(markerSize)
                        .background(color, CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable {
                            // Zoom sur le groupe
                            val lat = cl.map { it.first.resident.lastLatitude!! }.average()
                            val lon = cl.map { it.first.resident.lastLongitude!! }.average()
                            centerOn(lat, lon, zoomLevel + 2)
                        }
                        .testTag("map_cluster_${cl.size}")
                ) {
                    Text("${cl.size}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
        }

        // Styles de carte
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapTileStyle.entries.forEach { style ->
                val sel = currentStyle == style
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (sel) SafeNavy else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = if (sel) 6.dp else 2.dp,
                    modifier = Modifier.clickable { currentStyle = style }.testTag("style_chip_${style.name}")
                ) {
                    Text(style.displayName, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        // Commandes
        val outMarkers = markers.filter { it.status == ResidentStatus.OUT }.sortedBy { it.resident.exitedAt ?: 0L }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapButton(Icons.Default.Add, "Zoom avant", "btn_zoom_in") { if (zoomLevel < MAX_ZOOM) { zoomLevel++; subZoom = 1f; panOffsetX *= 2; panOffsetY *= 2 } }
            MapButton(Icons.Default.Remove, "Zoom arrière", "btn_zoom_out") { if (zoomLevel > MIN_ZOOM) { zoomLevel--; subZoom = 1f; panOffsetX /= 2; panOffsetY /= 2 } }
            Spacer(Modifier.height(4.dp))
            MapButton(Icons.Default.Apartment, "Recentrer sur l'établissement", "map_recenter_facility_button", SafeNavy, Color.White) {
                panOffsetX = 0f; panOffsetY = 0f; zoomLevel = 16; subZoom = 1f
            }
            if (markers.isNotEmpty()) {
                MapButton(Icons.Default.FitScreen, "Afficher tous les résidents", "map_fit_all_button") { fitAll() }
            }
            if (outMarkers.isNotEmpty()) {
                MapButton(Icons.Default.SkipNext, "Résident hors zone suivant (${outMarkers.size})", "map_next_alert_button", c.dangerSolid, Color.White) {
                    val m = outMarkers[alertCycleIndex % outMarkers.size]
                    alertCycleIndex++
                    onSelectResident(m.resident.id)
                    centerOn(m.resident.lastLatitude!!, m.resident.lastLongitude!!)
                }
            }
        }

        // Échelle
        val mpp = MapTileProvider.metersPerPixel(centerLat, zoomLevel) / subZoom
        val scaleMeters = listOf(10, 20, 50, 100, 200, 500, 1000, 2000).firstOrNull { it / mpp > with(density) { 60.dp.toPx() } } ?: 2000
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 54.dp)
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Box(Modifier.width(with(density) { (scaleMeters / mpp).toFloat().toDp() }).height(4.dp).background(MaterialTheme.colorScheme.onSurface))
                Text(if (scaleMeters >= 1000) "${scaleMeters / 1000} km" else "$scaleMeters m", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    container: Color = MaterialTheme.colorScheme.surface,
    content: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        shape = CircleShape,
        modifier = Modifier.size(52.dp).testTag(tag)
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp)) }
}
