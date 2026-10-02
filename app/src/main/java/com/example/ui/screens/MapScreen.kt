package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Resident
import com.example.ui.components.InteractiveCompassMap
import com.example.ui.components.VisualZonePickerDialog
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.ui.viewmodel.ResidentViewModel
import com.example.util.GeoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: ResidentViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val residents by viewModel.residents.collectAsState()
    val facilityZone by viewModel.facilityZone.collectAsState()
    val selectedResident by viewModel.selectedResident.collectAsState()

    var showVisualZonePicker by remember { mutableStateOf(false) }
    var isCardVisible by remember { mutableStateOf(true) }
    var isCardExpanded by remember { mutableStateOf(true) }

    // Toujours résoudre l'état frais du résident depuis la liste réactive
    val currentFocusedResident = selectedResident?.let { sel ->
        residents.find { it.id == sel.id } ?: sel
    } ?: residents.find { !it.isInZone } ?: residents.firstOrNull()

    fun launchNavigation(resident: Resident) {
        val lat = resident.lastLatitude ?: facilityZone.centerLatitude
        val lon = resident.lastLongitude ?: facilityZone.centerLongitude
        val uri = Uri.parse("google.navigation:q=$lat,$lon&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val browserUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
            context.startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Carte radar interactive", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                        Text(
                            text = "${facilityZone.name} • Rayon ${facilityZone.radiusMeters.toInt()}m",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showVisualZonePicker = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Régler la zone sur carte", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.refreshAllPositions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser positions", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Interactive Map Canvas
            InteractiveCompassMap(
                facilityZone = facilityZone,
                residents = residents,
                selectedResident = currentFocusedResident,
                onSelectResident = {
                    viewModel.selectResident(it)
                    isCardVisible = true
                },
                modifier = Modifier.fillMaxSize()
            )

            // Bottom Floating Panel for Focused Resident
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Resident Quick Selector Pills
                if (residents.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        items(residents) { res ->
                            val isSelected = currentFocusedResident?.id == res.id
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = when {
                                    !res.isInZone -> AlertRed
                                    isSelected -> SafeNavy
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.selectResident(res)
                                        isCardVisible = true
                                    }
                                    .testTag("map_select_pill_${res.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (!res.isInZone) Color.White else SafeGreen,
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = res.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!res.isInZone || isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Selected Resident Detail Card (Repliable et Fermable)
                if (currentFocusedResident != null) {
                    val res = currentFocusedResident
                    val isOutside = !res.isInZone

                    AnimatedVisibility(
                        visible = isCardVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("map_focused_resident_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // Header: Nom, État, Distance, Boutons Réduire et Fermer
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isOutside) Icons.Default.Warning else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = if (isOutside) AlertRed else SafeGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = res.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            Text(
                                                text = if (isOutside) "🚨 HORS ZONE DE SÉCURITÉ" else "🟢 Dans l'établissement",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = if (isOutside) AlertRed else SafeGreen
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp)) {
                                            Text(
                                                text = "À ${res.distanceFromCenterMeters.toInt()}m",
                                                fontWeight = FontWeight.Black,
                                                fontSize = 14.sp,
                                                color = if (isOutside) AlertRed else MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = if (res.lastBattery != null) "Batterie ${res.lastBattery}%" else "Batterie --",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Bouton Réduire / Déplier
                                        IconButton(
                                            onClick = { isCardExpanded = !isCardExpanded },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isCardExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                contentDescription = if (isCardExpanded) "Réduire" else "Déplier",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Bouton Fermer le panneau
                                        IconButton(
                                            onClick = { isCardVisible = false },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Fermer le panneau",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Contenu dépliable avec boutons d'actions
                                AnimatedVisibility(visible = isCardExpanded) {
                                    Column {
                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Action buttons with guaranteed touch target >= 48dp
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // 1. Bouton Guider
                                            ElevatedButton(
                                                onClick = { launchNavigation(res) },
                                                modifier = Modifier
                                                    .weight(1.1f)
                                                    .height(48.dp)
                                                    .testTag("map_navigate_btn"),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                colors = ButtonDefaults.elevatedButtonColors(
                                                    containerColor = if (isOutside) AlertRed else SafeNavy,
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Guider",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }

                                            // 2. Bouton Lever alerte si dehors, sinon Sonner
                                            if (isOutside) {
                                                ElevatedButton(
                                                    onClick = {
                                                        viewModel.resolveAlertForResident(res)
                                                    },
                                                    modifier = Modifier
                                                        .weight(1.3f)
                                                        .height(48.dp)
                                                        .testTag("map_resolve_alert_btn"),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    colors = ButtonDefaults.elevatedButtonColors(
                                                        containerColor = SafeGreen,
                                                        contentColor = Color.White
                                                    ),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "Sécurisé ✅",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }

                                            // 3. Bouton Sonner
                                            FilledTonalButton(
                                                onClick = { viewModel.ringTracker(res) },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                                    .testTag("map_ring_btn"),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Sonner",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }

                                            // 4. Bouton SuperLive
                                            FilledTonalButton(
                                                onClick = { viewModel.activateSuperLive(res) },
                                                modifier = Modifier
                                                    .weight(1.1f)
                                                    .height(48.dp)
                                                    .testTag("map_superlive_btn"),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "SuperLive",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Petit bouton flottant pour ré-afficher le panneau si fermé
                    if (!isCardVisible) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = SafeNavy,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp)
                                .clickable {
                                    isCardVisible = true
                                    isCardExpanded = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Afficher infos ${res.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVisualZonePicker) {
        VisualZonePickerDialog(
            currentZone = facilityZone,
            onDismiss = { showVisualZonePicker = false },
            onZoneSaved = { updatedZone ->
                viewModel.updateFacilityZone(updatedZone)
                showVisualZonePicker = false
            }
        )
    }
}
