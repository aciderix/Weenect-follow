package fr.alerteresidents.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.ui.theme.MyApplicationTheme
import fr.alerteresidents.util.GeoUtils

/**
 * Édition de la zone sur PC : clic sur la carte pour placer le centre (cercle) ou les points
 * du périmètre (polygone), puis zones annexes, mode nuit et fréquence de vérification.
 */
@Composable
fun DesktopZoneEditor(currentZone: FacilityZone, onDismiss: () -> Unit, onSave: (FacilityZone) -> Unit) {
    var name by remember { mutableStateOf(currentZone.name) }
    var address by remember { mutableStateOf(currentZone.address) }
    var polygonMode by remember { mutableStateOf(currentZone.isPolygon) }
    var centerLat by remember { mutableStateOf(currentZone.centerLatitude) }
    var centerLon by remember { mutableStateOf(currentZone.centerLongitude) }
    var radius by remember { mutableFloatStateOf(currentZone.radiusMeters.toFloat()) }
    var points by remember { mutableStateOf(currentZone.getPolygonPoints()) }
    var zoneActive by remember { mutableStateOf(currentZone.isZoneActive) }
    var refreshInterval by remember { mutableIntStateOf(currentZone.refreshIntervalSeconds) }
    var extraZones by remember { mutableStateOf(currentZone.getExtraZones()) }
    var nightEnabled by remember { mutableStateOf(currentZone.nightModeEnabled) }
    var nightStart by remember { mutableIntStateOf(currentZone.nightStartHour) }
    var nightEnd by remember { mutableIntStateOf(currentZone.nightEndHour) }
    var nightInterval by remember { mutableIntStateOf(currentZone.nightRefreshIntervalSeconds) }

    fun draft(): FacilityZone {
        val poly = polygonMode && points.size >= 3
        val (cLat, cLon) = if (poly) GeoUtils.calculatePolygonCenter(points) else centerLat to centerLon
        return currentZone.copy(
            name = name.trim().ifEmpty { FacilityZone.DEFAULT_NAME },
            address = address.trim(),
            zoneType = if (poly) "POLYGON" else "CIRCLE",
            polygonPointsJson = if (poly) FacilityZone.encodePolygonPoints(points) else "",
            centerLatitude = cLat,
            centerLongitude = cLon,
            radiusMeters = radius.toDouble(),
            isZoneActive = zoneActive,
            refreshIntervalSeconds = refreshInterval,
            extraZonesJson = FacilityZone.encodeExtraZones(extraZones),
            nightModeEnabled = nightEnabled,
            nightStartHour = nightStart,
            nightEndHour = nightEnd,
            nightRefreshIntervalSeconds = nightInterval
        )
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Zone de sécurité — ${currentZone.name}",
        state = rememberDialogState(size = DpSize(1200.dp, 800.dp))
    ) {
        MyApplicationTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom de l'établissement") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(address, { address = it }, label = { Text("Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Forme de la zone", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !polygonMode, onClick = { polygonMode = false }, label = { Text("Cercle") })
                            FilterChip(selected = polygonMode, onClick = { polygonMode = true }, label = { Text("Périmètre tracé") })
                        }
                        if (!polygonMode) {
                            Text("Cliquez sur la carte pour placer le centre.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Rayon : ${radius.toInt()} m", fontWeight = FontWeight.SemiBold)
                            Slider(value = radius, onValueChange = { radius = it }, valueRange = 30f..800f)
                        } else {
                            Text(
                                "Cliquez sur la carte pour ajouter les points du périmètre, dans l'ordre (au moins 3). ${points.size} point(s).",
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { points = points.dropLast(1) }, enabled = points.isNotEmpty()) { Text("Annuler le dernier point") }
                                TextButton(onClick = { points = emptyList() }, enabled = points.isNotEmpty()) { Text("Tout effacer") }
                            }
                        }
                        ZoneExtrasSection(
                            zoneActive = zoneActive, onZoneActive = { zoneActive = it },
                            refreshInterval = refreshInterval, onRefreshInterval = { refreshInterval = it },
                            extraZones = extraZones, onExtraZones = { extraZones = it },
                            defaultCenter = centerLat to centerLon,
                            nightEnabled = nightEnabled, onNightEnabled = { nightEnabled = it },
                            nightStart = nightStart, onNightStart = { nightStart = it },
                            nightEnd = nightEnd, onNightEnd = { nightEnd = it },
                            nightInterval = nightInterval, onNightInterval = { nightInterval = it }
                        )
                        Spacer(Modifier.padding(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDismiss) { Text("Annuler") }
                            Button(
                                onClick = { onSave(draft()) },
                                enabled = !polygonMode || points.size >= 3
                            ) { Text("Enregistrer la zone") }
                        }
                    }
                    InteractiveCompassMap(
                        facilityZone = draft(),
                        markers = emptyList(),
                        selectedResidentId = null,
                        history = null,
                        onSelectResident = {},
                        onMapClick = { lat, lon ->
                            if (polygonMode) points = points + (lat to lon) else { centerLat = lat; centerLon = lon }
                        },
                        editPoints = if (polygonMode) points else emptyList(),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}
