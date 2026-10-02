package fr.alerteresidents.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.ui.components.BatteryPill
import fr.alerteresidents.ui.components.InteractiveCompassMap
import fr.alerteresidents.ui.components.MapMarker
import fr.alerteresidents.ui.components.ResidentAvatar
import fr.alerteresidents.ui.components.statusDetail
import fr.alerteresidents.ui.components.statusStyle
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.util.GeoUtils
import fr.alerteresidents.util.MapsNavigator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: ResidentViewModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val residents by viewModel.residents.collectAsState()
    val facilityZone by viewModel.facilityZone.collectAsState()
    val selectedId by viewModel.selectedResidentId.collectAsState()
    val history by viewModel.history.collectAsState()
    val now by viewModel.now.collectAsState()
    var cardExpanded by rememberSaveable { mutableStateOf(true) }
    var historyHours by remember { mutableStateOf<Int?>(null) }

    val staleMinutes = viewModel.prefs.staleMinutes
    val sorted = residents.filter { it.isTrackingActive }.sortedWith(ResidentStatusResolver.urgencyComparator(now, staleMinutes))
    val statuses = sorted.associate { it.id to ResidentStatusResolver.resolve(it, now, staleMinutes) }
    // Seules les positions réelles sont dessinées : jamais de résident « posé » au centre par défaut
    val markers = sorted.filter { it.hasPosition }.map { MapMarker(it, statuses.getValue(it.id)) }
    val withoutPosition = sorted.filter { !it.hasPosition }
    val focused = sorted.find { it.id == selectedId } ?: sorted.firstOrNull { statuses[it.id] == ResidentStatus.OUT }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Carte", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(
                            "${markers.size} résident(s) localisé(s)" + if (withoutPosition.isNotEmpty()) " • ${withoutPosition.size} sans position" else "",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) } },
                actions = {
                    IconButton(onClick = { viewModel.refreshAllPositions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser les positions", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            InteractiveCompassMap(
                facilityZone = facilityZone,
                markers = markers,
                selectedResidentId = focused?.id,
                history = history?.takeIf { it.first == focused?.id && historyHours != null }?.second,
                onSelectResident = { id ->
                    viewModel.selectResident(id)
                    if (history?.first != id) { historyHours = null; viewModel.clearHistory() }
                    cardExpanded = true
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                if (sorted.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        items(sorted, key = { it.id }) { r ->
                            val style = statusStyle(statuses.getValue(r.id))
                            val isSel = focused?.id == r.id
                            FilterChip(
                                selected = isSel,
                                onClick = { viewModel.selectResident(r.id); cardExpanded = true },
                                label = { Text(r.name, fontSize = 14.sp, maxLines = 1) },
                                leadingIcon = { Icon(style.icon, contentDescription = null, tint = style.accent, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.testTag("map_select_pill_${r.id}")
                            )
                        }
                    }
                }
                focused?.let { r ->
                    val status = statuses.getValue(r.id)
                    val style = statusStyle(status)
                    AnimatedVisibility(visible = true) {
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("map_focused_resident_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ResidentAvatar(r, 44.dp, ringColor = style.accent)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${status.label} • ${statusDetail(r, status, now)}", fontSize = 13.sp, color = style.accent,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    }
                                    BatteryPill(r.lastBattery)
                                    IconButton(onClick = { cardExpanded = !cardExpanded }) {
                                        Icon(if (cardExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                            contentDescription = if (cardExpanded) "Réduire" else "Agrandir")
                                    }
                                }
                                if (cardExpanded) {
                                    if (r.hasPosition) {
                                        Text(
                                            buildString {
                                                append("${GeoUtils.formatDistance(r.distanceFromCenterMeters)} ")
                                                append(if (facilityZone.isPolygon) "de la limite" else "du centre")
                                                r.accuracyMeters?.let { append(" • précision ±$it m") }
                                                r.lastSpeed?.takeIf { it > 0.5 }?.let { append(" • ${"%.1f".format(it)} km/h") }
                                            },
                                            fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (status == ResidentStatus.OUT && r.alertState == AlertState.ACTIVE) {
                                            Button(
                                                onClick = { viewModel.handleAlert(r.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = style.solid, contentColor = Color.White),
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                                modifier = Modifier.weight(1.3f).heightIn(min = 48.dp)
                                            ) {
                                                Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp)); Text("Je m'en occupe", fontSize = 14.sp, maxLines = 1)
                                            }
                                        } else if (status == ResidentStatus.OUT && r.alertState == AlertState.HANDLING) {
                                            Button(
                                                onClick = { viewModel.markFound(r.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                                modifier = Modifier.weight(1.3f).heightIn(min = 48.dp)
                                            ) { Text("Retrouvé", fontSize = 14.sp) }
                                        }
                                        Button(
                                            onClick = { MapsNavigator.navigate(r.lastLatitude!!, r.lastLongitude!!) },
                                            enabled = r.hasPosition,
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp)); Text("Guider", fontSize = 14.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = { viewModel.ringTracker(r) },
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp)); Text("Sonner", fontSize = 14.sp)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(onClick = { viewModel.activateSuperLive(r) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp)); Text("SuperLive", fontSize = 14.sp)
                                        }
                                        Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                                        listOf(2, 6).forEach { h ->
                                            FilterChip(
                                                selected = historyHours == h,
                                                onClick = {
                                                    if (historyHours == h) { historyHours = null; viewModel.clearHistory() }
                                                    else { historyHours = h; viewModel.loadHistory(r, h) }
                                                },
                                                label = { Text("Trajet $h h", fontSize = 14.sp) }
                                            )
                                        }
                                        if (historyHours != null) {
                                            IconButton(onClick = { historyHours = null; viewModel.clearHistory() }) {
                                                Icon(Icons.Default.Close, contentDescription = "Masquer le trajet")
                                            }
                                        }
                                    }
                                    if (withoutPosition.isNotEmpty()) {
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                            Text("Sans position (non affichés) : ${withoutPosition.joinToString { it.name }}",
                                                fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
