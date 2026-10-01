package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.util.GeoUtils
import com.example.util.MapTileProvider
import com.example.util.MapTileStyle
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun InteractiveCompassMap(
    facilityZone: FacilityZone,
    residents: List<Resident>,
    selectedResident: Resident?,
    onSelectResident: (Resident) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tileProvider = remember { MapTileProvider(context) }

    // Map style
    var currentStyle by remember { mutableStateOf(MapTileStyle.OPEN_STREET_MAP) }

    // Integer zoom level (14 to 18 is ideal for facility surveillance)
    var zoomLevel by remember { mutableIntStateOf(16) }
    // Sub-zoom fine scaling
    var subZoom by remember { mutableFloatStateOf(1.0f) }

    // Viewport panning offsets in screen pixels
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Pulsing animation for outside-zone emergency indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 54f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    // Center coordinates
    val centerLat = facilityZone.centerLatitude
    val centerLon = facilityZone.centerLongitude

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("interactive_compass_map")
            .pointerInput(zoomLevel) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    panOffsetX += pan.x
                    panOffsetY += pan.y

                    val newSubZoom = subZoom * zoomChange
                    if (newSubZoom > 1.7f && zoomLevel < 18) {
                        zoomLevel += 1
                        subZoom = 1.0f
                        panOffsetX *= 0.5f
                        panOffsetY *= 0.5f
                    } else if (newSubZoom < 0.65f && zoomLevel > 14) {
                        zoomLevel -= 1
                        subZoom = 1.0f
                        panOffsetX *= 2f
                        panOffsetY *= 2f
                    } else {
                        subZoom = newSubZoom.coerceIn(0.7f, 1.6f)
                    }
                }
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val screenCenterX = width / 2f + panOffsetX
        val screenCenterY = height / 2f + panOffsetY

        // Trigger recomposition on tile arrival
        val tileCacheVersion = tileProvider.loadedTiles.size

        // Calculate world coordinates for facility center
        val (centerWorldX, centerWorldY) = MapTileProvider.latLonToWorld(centerLat, centerLon, zoomLevel)

        // Draw real map tiles and vector overlays
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read version to ensure recomposition when tiles arrive
            @Suppress("UNUSED_VARIABLE")
            val v = tileCacheVersion

            // 1. Render Real OpenStreetMap / Satellite Tiles
            val tileSize = (MapTileProvider.TILE_SIZE * subZoom).toFloat()

            // Viewport bounds in world coordinates
            val minWorldX = centerWorldX - (screenCenterX / subZoom)
            val maxWorldX = centerWorldX + ((width - screenCenterX) / subZoom)
            val minWorldY = centerWorldY - (screenCenterY / subZoom)
            val maxWorldY = centerWorldY + ((height - screenCenterY) / subZoom)

            val minTileX = floor(minWorldX / MapTileProvider.TILE_SIZE).toInt()
            val maxTileX = floor(maxWorldX / MapTileProvider.TILE_SIZE).toInt()
            val minTileY = floor(minWorldY / MapTileProvider.TILE_SIZE).toInt()
            val maxTileY = floor(maxWorldY / MapTileProvider.TILE_SIZE).toInt()

            val maxTileIndex = (1 shl zoomLevel) - 1

            for (tx in minTileX..maxTileX) {
                if (tx < 0 || tx > maxTileIndex) continue
                for (ty in minTileY..maxTileY) {
                    if (ty < 0 || ty > maxTileIndex) continue

                    // Screen position for tile top-left
                    val tileWorldX = tx * MapTileProvider.TILE_SIZE
                    val tileWorldY = ty * MapTileProvider.TILE_SIZE

                    val drawX = screenCenterX + ((tileWorldX - centerWorldX) * subZoom).toFloat()
                    val drawY = screenCenterY + ((tileWorldY - centerWorldY) * subZoom).toFloat()

                    val tileBitmap = tileProvider.getTile(zoomLevel, tx, ty, currentStyle)
                    if (tileBitmap != null) {
                        drawImage(
                            image = tileBitmap,
                            dstOffset = IntOffset(drawX.roundToInt(), drawY.roundToInt()),
                            dstSize = IntSize(tileSize.roundToInt(), tileSize.roundToInt())
                        )
                    } else {
                        // Sleek placeholder grid during loading
                        val bgPlaceholder = if (currentStyle == MapTileStyle.SATELLITE) Color(0xFF1E262C) else Color(0xFFE2E8F0)
                        drawRect(
                            color = bgPlaceholder,
                            topLeft = Offset(drawX, drawY),
                            size = Size(tileSize, tileSize)
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.2f),
                            topLeft = Offset(drawX, drawY),
                            size = Size(tileSize, tileSize),
                            style = Stroke(width = 1f)
                        )
                    }
                }
            }

            // 2. Security Perimeter (Custom Polygon or Exact Ground Scale Circle)
            if (facilityZone.zoneType == "POLYGON" && facilityZone.getPolygonPoints().size >= 3) {
                val polyPoints = facilityZone.getPolygonPoints()
                val screenPoints = polyPoints.map { (pLat, pLon) ->
                    val (pWorldX, pWorldY) = MapTileProvider.latLonToWorld(pLat, pLon, zoomLevel)
                    val sX = screenCenterX + ((pWorldX - centerWorldX) * subZoom).toFloat()
                    val sY = screenCenterY + ((pWorldY - centerWorldY) * subZoom).toFloat()
                    Offset(sX, sY)
                }
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(screenPoints[0].x, screenPoints[0].y)
                    for (i in 1 until screenPoints.size) {
                        lineTo(screenPoints[i].x, screenPoints[i].y)
                    }
                    close()
                }
                drawPath(
                    path = path,
                    color = SafeGreen.copy(alpha = if (currentStyle == MapTileStyle.SATELLITE) 0.30f else 0.22f),
                    style = androidx.compose.ui.graphics.drawscope.Fill
                )
                drawPath(
                    path = path,
                    color = SafeGreen,
                    style = Stroke(width = 3.5.dp.toPx())
                )
                screenPoints.forEach { pt ->
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                    drawCircle(color = SafeNavy, radius = 4.dp.toPx(), center = pt)
                }
            } else {
                val mpp = MapTileProvider.metersPerPixel(centerLat, zoomLevel)
                val zoneRadiusPx = (facilityZone.radiusMeters / mpp).toFloat() * subZoom

                // Translucent green protective zone
                drawCircle(
                    color = SafeGreen.copy(alpha = if (currentStyle == MapTileStyle.SATELLITE) 0.25f else 0.18f),
                    radius = zoneRadiusPx,
                    center = Offset(screenCenterX, screenCenterY)
                )
                // Solid crisp safety border
                drawCircle(
                    color = SafeGreen,
                    radius = zoneRadiusPx,
                    center = Offset(screenCenterX, screenCenterY),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Concentric 50m / 100m guide ring
                val guideRadiusPx = (50.0 / mpp).toFloat() * subZoom
                drawCircle(
                    color = SafeGreen.copy(alpha = 0.35f),
                    radius = guideRadiusPx,
                    center = Offset(screenCenterX, screenCenterY),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )
            }

            // Center: Facility Building Core Marker
            drawCircle(
                color = SafeNavy,
                radius = 16.dp.toPx(),
                center = Offset(screenCenterX, screenCenterY)
            )
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(screenCenterX, screenCenterY)
            )

            // 3. Resident Pins and Trajectories
            residents.forEach { res ->
                val resLat = res.lastLatitude ?: centerLat
                val resLon = res.lastLongitude ?: centerLon

                val (resWorldX, resWorldY) = MapTileProvider.latLonToWorld(resLat, resLon, zoomLevel)
                val resPxX = screenCenterX + ((resWorldX - centerWorldX) * subZoom).toFloat()
                val resPxY = screenCenterY + ((resWorldY - centerWorldY) * subZoom).toFloat()

                val isOutside = !res.isInZone
                val pinColor = if (isOutside) AlertRed else SafeGreen

                // If outside zone, draw dashed trail line + pulsing red radar rings
                if (isOutside) {
                    drawLine(
                        color = AlertRed.copy(alpha = 0.85f),
                        start = Offset(screenCenterX, screenCenterY),
                        end = Offset(resPxX, resPxY),
                        strokeWidth = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )

                    // Big pulsing emergency wave
                    drawCircle(
                        color = AlertRed.copy(alpha = pulseAlpha),
                        radius = pulseRadius * subZoom.coerceAtLeast(0.9f),
                        center = Offset(resPxX, resPxY)
                    )
                }

                // Resident Outer Pin Circle
                drawCircle(
                    color = pinColor,
                    radius = 16.dp.toPx(),
                    center = Offset(resPxX, resPxY)
                )
                // White Ring Accent
                drawCircle(
                    color = Color.White,
                    radius = 14.dp.toPx(),
                    center = Offset(resPxX, resPxY),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                // Center Core Dot
                drawCircle(
                    color = pinColor,
                    radius = 6.dp.toPx(),
                    center = Offset(resPxX, resPxY)
                )
            }
        }

        // Overlay: Center Facility Badge
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = panOffsetX.dp, y = (panOffsetY - 36).dp),
            colors = CardDefaults.cardColors(containerColor = SafeNavy.copy(alpha = 0.95f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Apartment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = facilityZone.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = SafeGreen,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${facilityZone.radiusMeters.toInt()}m",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Overlay: Resident Labels (with clean offsets, no text truncation)
        residents.forEach { res ->
            val resLat = res.lastLatitude ?: centerLat
            val resLon = res.lastLongitude ?: centerLon
            val (resWorldX, resWorldY) = MapTileProvider.latLonToWorld(resLat, resLon, zoomLevel)
            val resPxX = screenCenterX + ((resWorldX - centerWorldX) * subZoom).toFloat()
            val resPxY = screenCenterY + ((resWorldY - centerWorldY) * subZoom).toFloat()

            val isOutside = !res.isInZone
            val dist = res.distanceFromCenterMeters

            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(resPxX.toInt() - 48, resPxY.toInt() + 22)
                    }
                    .clickable { onSelectResident(res) }
                    .testTag("map_marker_${res.id}"),
                shape = RoundedCornerShape(8.dp),
                color = if (isOutside) AlertRed else MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isOutside) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alerte",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = res.name,
                        color = if (isOutside) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${dist.toInt()}m",
                        color = if (isOutside) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // Top Header: Style Switcher Chips
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapTileStyle.values().forEach { style ->
                val isSelected = currentStyle == style
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) SafeNavy else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = if (isSelected) 6.dp else 2.dp,
                    modifier = Modifier
                        .clickable { currentStyle = style }
                        .testTag("style_chip_${style.name}")
                ) {
                    Text(
                        text = style.displayName,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Right Edge Floating Controls (Zoom, Recentrer, Style)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Zoom In Button (Touch target 52dp)
            FilledIconButton(
                onClick = {
                    if (zoomLevel < 18) {
                        zoomLevel += 1
                        subZoom = 1.0f
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = CircleShape,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("btn_zoom_in")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom avant", modifier = Modifier.size(24.dp))
            }

            // Zoom Out Button
            FilledIconButton(
                onClick = {
                    if (zoomLevel > 14) {
                        zoomLevel -= 1
                        subZoom = 1.0f
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = CircleShape,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("btn_zoom_out")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom arrière", modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Recenter on Facility Center
            FilledIconButton(
                onClick = {
                    panOffsetX = 0f
                    panOffsetY = 0f
                    zoomLevel = 16
                    subZoom = 1.0f
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SafeNavy,
                    contentColor = Color.White
                ),
                shape = CircleShape,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("map_recenter_facility_button")
            ) {
                Icon(Icons.Default.Apartment, contentDescription = "Recentrer sur l'établissement", modifier = Modifier.size(22.dp))
            }

            // Recenter on Resident in Alert or First Resident
            if (residents.isNotEmpty()) {
                FilledIconButton(
                    onClick = {
                        val target = residents.find { !it.isInZone } ?: selectedResident ?: residents.first()
                        val resLat = target.lastLatitude ?: centerLat
                        val resLon = target.lastLongitude ?: centerLon
                        val (rWorldX, rWorldY) = MapTileProvider.latLonToWorld(resLat, resLon, zoomLevel)

                        panOffsetX = -((rWorldX - centerWorldX) * subZoom).toFloat()
                        panOffsetY = -((rWorldY - centerWorldY) * subZoom).toFloat()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (residents.any { !it.isInZone }) AlertRed else MaterialTheme.colorScheme.surface,
                        contentColor = if (residents.any { !it.isInZone }) Color.White else MaterialTheme.colorScheme.primary
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("map_recenter_resident_button")
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Recentrer sur le résident", modifier = Modifier.size(22.dp))
                }
            }
        }

        // Bottom Left Legend & Scale
        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 100.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(SafeGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Zone sûre (${facilityZone.radiusMeters.toInt()}m)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val mpp = MapTileProvider.metersPerPixel(centerLat, zoomLevel) / subZoom
                val scaleMeters = (60 * mpp).roundToInt()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Échelle : ~$scaleMeters m",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
