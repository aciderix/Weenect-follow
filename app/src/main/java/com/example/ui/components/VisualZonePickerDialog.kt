package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FacilityZone
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.util.AddressSearchHelper
import com.example.util.AddressSuggestion
import com.example.util.GeoUtils
import com.example.util.LocationHelper
import com.example.util.MapTileProvider
import com.example.util.MapTileStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Sélecteur visuel interactif complet :
 * - Recherche d'adresse automatique (ex: "1 rue Urbain le Verrier, Bouguenais")
 * - Mode Cercle ou Mode Polygone personnalisé (dessin des limites réelles du parc)
 * - Placement au GPS réel du téléphone
 */
@Composable
fun VisualZonePickerDialog(
    currentZone: FacilityZone,
    onDismiss: () -> Unit,
    onZoneSaved: (FacilityZone) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tileProvider = remember { MapTileProvider(context) }

    var selectedMode by remember { mutableStateOf(if (currentZone.zoneType == "POLYGON") 1 else 0) } // 0 = Cercle, 1 = Polygone

    var centerLat by remember { mutableDoubleStateOf(currentZone.centerLatitude) }
    var centerLon by remember { mutableDoubleStateOf(currentZone.centerLongitude) }
    var radiusMeters by remember { mutableFloatStateOf(currentZone.radiusMeters.toFloat()) }

    // Sommets du polygone
    val polygonPoints = remember {
        mutableStateListOf<Pair<Double, Double>>().apply {
            addAll(currentZone.getPolygonPoints())
        }
    }

    // Recherche d'adresse
    var searchQuery by remember { mutableStateOf(currentZone.address) }
    var searchSuggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    var zoomLevel by remember { mutableIntStateOf(16) }
    var subZoom by remember { mutableFloatStateOf(1.0f) }
    var currentStyle by remember { mutableStateOf(MapTileStyle.OPEN_STREET_MAP) }
    var isLocating by remember { mutableStateOf(false) }

    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    val tileCacheVersion = tileProvider.loadedTiles.size

    fun latLonToWorldPx(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val scale = MapTileProvider.TILE_SIZE * (1 shl z)
        val clampedLon = lon.coerceIn(-180.0, 180.0)
        val worldX = (clampedLon + 180.0) / 360.0 * scale
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val sinLat = sin(Math.toRadians(clampedLat))
        val worldY = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale
        return Pair(worldX, worldY)
    }

    fun worldPxToLatLon(x: Double, y: Double, z: Int): Pair<Double, Double> {
        val scale = MapTileProvider.TILE_SIZE * (1 shl z)
        val lon = (x / scale * 360.0) - 180.0
        val latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / scale)))
        val lat = Math.toDegrees(latRad)
        return Pair(lat, lon)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenWidthPx = constraints.maxWidth.toFloat()
                val screenHeightPx = constraints.maxHeight.toFloat()
                val centerScreen = Offset(screenWidthPx / 2f, screenHeightPx / 2f)

                val (anchorWorldX, anchorWorldY) = latLonToWorldPx(centerLat, centerLon, zoomLevel)
                val targetWorldX = anchorWorldX - (panOffsetX / subZoom)
                val targetWorldY = anchorWorldY - (panOffsetY / subZoom)

                // Coordonnées GPS calculées sous le réticule central
                val (targetedLat, targetedLon) = worldPxToLatLon(targetWorldX, targetWorldY, zoomLevel)

                // Rayon en pixels à l'écran pour le mode cercle
                val metersPerPx = MapTileProvider.metersPerPixel(targetedLat, zoomLevel)
                val radiusPx = (radiusMeters / metersPerPx).toFloat() * subZoom

                // 1. Fond de Carte OpenStreetMap + Dessin Cercle ou Polygone
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(zoomLevel) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    panOffsetX += pan.x
                                    panOffsetY += pan.y

                                    val newSubZoom = subZoom * zoom
                                    if (newSubZoom > 1.7f && zoomLevel < 18) {
                                        zoomLevel += 1
                                        subZoom = 1.0f
                                        panOffsetX *= 0.5f
                                        panOffsetY *= 0.5f
                                    } else if (newSubZoom < 0.65f && zoomLevel > 13) {
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
                        @Suppress("UNUSED_VARIABLE")
                        val v = tileCacheVersion

                        val tileSize = (MapTileProvider.TILE_SIZE * subZoom).toFloat()
                        val minWorldX = targetWorldX - (centerScreen.x / subZoom)
                        val maxWorldX = targetWorldX + (centerScreen.x / subZoom)
                        val minWorldY = targetWorldY - (centerScreen.y / subZoom)
                        val maxWorldY = targetWorldY + (centerScreen.y / subZoom)

                        val minTileX = floor(minWorldX / MapTileProvider.TILE_SIZE).toInt()
                        val maxTileX = floor(maxWorldX / MapTileProvider.TILE_SIZE).toInt()
                        val minTileY = floor(minWorldY / MapTileProvider.TILE_SIZE).toInt()
                        val maxTileY = floor(maxWorldY / MapTileProvider.TILE_SIZE).toInt()

                        // A. Dessin des tuiles de carte
                        for (tx in minTileX..maxTileX) {
                            for (ty in minTileY..maxTileY) {
                                val tileBitmap = tileProvider.getTile(zoomLevel, tx, ty, currentStyle)
                                val tileScreenX = centerScreen.x + ((tx * MapTileProvider.TILE_SIZE - targetWorldX) * subZoom).toFloat()
                                val tileScreenY = centerScreen.y + ((ty * MapTileProvider.TILE_SIZE - targetWorldY) * subZoom).toFloat()

                                if (tileBitmap != null) {
                                    drawImage(
                                        image = tileBitmap,
                                        dstOffset = IntOffset(tileScreenX.toInt(), tileScreenY.toInt()),
                                        dstSize = IntSize(tileSize.toInt() + 1, tileSize.toInt() + 1)
                                    )
                                }
                            }
                        }

                        // B. Mode 0 : Dessin du cercle de sécurité
                        if (selectedMode == 0) {
                            drawCircle(
                                color = SafeGreen.copy(alpha = 0.22f),
                                radius = radiusPx,
                                center = centerScreen
                            )
                            drawCircle(
                                color = SafeGreen,
                                radius = radiusPx,
                                center = centerScreen,
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }

                        // C. Mode 1 : Dessin du polygone personnalisé (limites du parc)
                        if (selectedMode == 1 && polygonPoints.isNotEmpty()) {
                            val screenPoints = polygonPoints.map { (pLat, pLon) ->
                                val (pWorldX, pWorldY) = latLonToWorldPx(pLat, pLon, zoomLevel)
                                val sX = centerScreen.x + ((pWorldX - targetWorldX) * subZoom).toFloat()
                                val sY = centerScreen.y + ((pWorldY - targetWorldY) * subZoom).toFloat()
                                Offset(sX, sY)
                            }

                            if (screenPoints.size >= 3) {
                                val path = Path().apply {
                                    moveTo(screenPoints[0].x, screenPoints[0].y)
                                    for (i in 1 until screenPoints.size) {
                                        lineTo(screenPoints[i].x, screenPoints[i].y)
                                    }
                                    close()
                                }
                                drawPath(path, color = SafeGreen.copy(alpha = 0.28f), style = Fill)
                                drawPath(path, color = SafeGreen, style = Stroke(width = 3.dp.toPx()))
                            } else if (screenPoints.size == 2) {
                                drawLine(
                                    color = SafeGreen,
                                    start = screenPoints[0],
                                    end = screenPoints[1],
                                    strokeWidth = 3.dp.toPx()
                                )
                            }

                            // Dessiner les sommets / bornes
                            screenPoints.forEachIndexed { index, pt ->
                                drawCircle(
                                    color = Color.White,
                                    radius = 8.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = SafeNavy,
                                    radius = 6.dp.toPx(),
                                    center = pt
                                )
                            }
                        }

                        // D. Réticule central 🎯
                        drawCircle(
                            color = Color.White,
                            radius = 16.dp.toPx(),
                            center = centerScreen,
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = SafeNavy,
                            radius = 6.dp.toPx(),
                            center = centerScreen
                        )
                        drawLine(
                            color = SafeNavy,
                            start = Offset(centerScreen.x - 24.dp.toPx(), centerScreen.y),
                            end = Offset(centerScreen.x + 24.dp.toPx(), centerScreen.y),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawLine(
                            color = SafeNavy,
                            start = Offset(centerScreen.x, centerScreen.y - 24.dp.toPx()),
                            end = Offset(centerScreen.x, centerScreen.y + 24.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                // 2. Barre supérieure : Recherche d'adresse + Onglets Cercle/Polygone
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Carte recherche d'adresse
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { query ->
                                        searchQuery = query
                                        searchJob?.cancel()
                                        if (query.trim().length >= 3) {
                                            isSearching = true
                                            searchJob = scope.launch {
                                                delay(300) // Debounce
                                                val res = AddressSearchHelper.searchAddress(query)
                                                searchSuggestions = res
                                                isSearching = false
                                                showSuggestions = res.isNotEmpty()
                                            }
                                        } else {
                                            searchSuggestions = emptyList()
                                            showSuggestions = false
                                        }
                                    },
                                    placeholder = { Text("Rechercher adresse (ex: 1 rue Urbain le Verrier)", fontSize = 12.sp) },
                                    leadingIcon = {
                                        if (isSearching) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = {
                                                searchQuery = ""
                                                showSuggestions = false
                                            }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Effacer", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SafeNavy,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    )
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                                }
                            }

                            // Suggestions d'adresses déroulantes
                            if (showSuggestions && searchSuggestions.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .heightIn(max = 200.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    LazyColumn {
                                        items(searchSuggestions) { sugg ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        centerLat = sugg.latitude
                                                        centerLon = sugg.longitude
                                                        panOffsetX = 0f
                                                        panOffsetY = 0f
                                                        searchQuery = sugg.label
                                                        showSuggestions = false
                                                        Toast.makeText(context, "Carte centrée sur ${sugg.label}", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = SafeNavy, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(sugg.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Onglets : Mode Cercle ou Polygone
                            TabRow(
                                selectedTabIndex = selectedMode,
                                containerColor = Color.Transparent,
                                contentColor = SafeNavy
                            ) {
                                Tab(
                                    selected = selectedMode == 0,
                                    onClick = { selectedMode = 0 },
                                    text = { Text("⚪ Cercle (Rayon)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                )
                                Tab(
                                    selected = selectedMode == 1,
                                    onClick = { selectedMode = 1 },
                                    text = { Text("📐 Polygone (Parc)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }

                // 3. Boutons flottants latéraux (GPS actuel, Zoom, Styles)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledIconButton(
                        onClick = {
                            isLocating = true
                            scope.launch {
                                val loc = LocationHelper.getCurrentLocation(context)
                                isLocating = false
                                if (loc != null) {
                                    centerLat = loc.latitude
                                    centerLon = loc.longitude
                                    panOffsetX = 0f
                                    panOffsetY = 0f
                                    Toast.makeText(context, "Position GPS fixée", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Impossible d'obtenir le GPS", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.size(46.dp)
                    ) {
                        if (isLocating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.MyLocation, contentDescription = "Ma position")
                        }
                    }

                    FilledIconButton(
                        onClick = { if (zoomLevel < 18) zoomLevel++ },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom plus")
                    }

                    FilledIconButton(
                        onClick = { if (zoomLevel > 13) zoomLevel-- },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom moins")
                    }

                    FilledIconButton(
                        onClick = {
                            currentStyle = when (currentStyle) {
                                MapTileStyle.OPEN_STREET_MAP -> MapTileStyle.OSM_FR
                                MapTileStyle.OSM_FR -> MapTileStyle.SATELLITE
                                MapTileStyle.SATELLITE -> MapTileStyle.OPEN_STREET_MAP
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = "Style de carte")
                    }
                }

                // 4. Panneau inférieur de configuration
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (selectedMode == 0) {
                            // Configuration Mode Cercle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Rayon de sécurité :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Surface(
                                    color = SafeGreen.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${radiusMeters.toInt()} mètres",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp,
                                        color = SafeGreen,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Slider(
                                value = radiusMeters,
                                onValueChange = { radiusMeters = it },
                                valueRange = 30f..800f,
                                steps = 15,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(50, 100, 150, 250, 500).forEach { r ->
                                    FilterChip(
                                        selected = radiusMeters.toInt() == r,
                                        onClick = { radiusMeters = r.toFloat() },
                                        label = { Text("${r}m", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SafeGreen,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        } else {
                            // Configuration Mode Polygone (Tracé du parc)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Tracé des limites du parc :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "${polygonPoints.size} borne(s) placée(s) (min 3 requises)",
                                        fontSize = 11.sp,
                                        color = if (polygonPoints.size >= 3) SafeGreen else AlertRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (polygonPoints.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { polygonPoints.removeLastOrNull() },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Undo, contentDescription = "Annuler", modifier = Modifier.size(14.dp))
                                        }
                                        OutlinedButton(
                                            onClick = { polygonPoints.clear() },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Effacer", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    polygonPoints.add(Pair(targetedLat, targetedLon))
                                    Toast.makeText(context, "Borne ${polygonPoints.size} ajoutée", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Placer une borne sous le réticule 🎯", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Bouton principal de validation
                        Button(
                            onClick = {
                                val isPolygon = selectedMode == 1 && polygonPoints.size >= 3
                                val finalCenter = if (isPolygon) {
                                    GeoUtils.calculatePolygonCenter(polygonPoints)
                                } else {
                                    Pair(targetedLat, targetedLon)
                                }

                                val updated = currentZone.copy(
                                    name = currentZone.name.ifBlank { "MAS l'Épeau (Bouguenais)" },
                                    address = searchQuery.ifBlank { currentZone.address },
                                    centerLatitude = finalCenter.first,
                                    centerLongitude = finalCenter.second,
                                    radiusMeters = radiusMeters.toDouble(),
                                    zoneType = if (isPolygon) "POLYGON" else "CIRCLE",
                                    polygonPointsJson = if (isPolygon) FacilityZone.encodePolygonPoints(polygonPoints) else ""
                                )
                                onZoneSaved(updated)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SafeNavy,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            val label = if (selectedMode == 1 && polygonPoints.size >= 3) {
                                "Valider le polygone du parc (${polygonPoints.size} bornes)"
                            } else {
                                "Valider la zone circulaire (${radiusMeters.toInt()}m)"
                            }
                            Text(
                                text = label,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
