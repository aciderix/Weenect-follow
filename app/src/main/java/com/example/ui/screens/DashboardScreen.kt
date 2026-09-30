package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.ui.components.AddResidentDialog
import com.example.ui.components.AlertBanner
import com.example.ui.components.ResidentCard
import com.example.ui.components.ZoneEditorDialog
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.ui.viewmodel.ResidentViewModel

enum class ResidentFilter {
    ALL,
    OUT_OF_ZONE,
    IN_ZONE,
    LOW_BATTERY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ResidentViewModel,
    onNavigateToMap: (Resident?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val residents by viewModel.residents.collectAsState()
    val facilityZone by viewModel.facilityZone.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isAlarmRinging by viewModel.isAlarmRinging.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var residentToEdit by remember { mutableStateOf<Resident?>(null) }
    var showZoneEditor by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(ResidentFilter.ALL) }

    val outOfZoneResidents = residents.filter { !it.isInZone }
    val inZoneResidents = residents.filter { it.isInZone }
    val lowBatteryResidents = residents.filter { (it.lastBattery ?: 100) <= 25 }

    val filteredResidents = residents.filter { res ->
        val matchesSearch = searchQuery.isBlank() ||
                res.name.contains(searchQuery, ignoreCase = true) ||
                res.roomNumber.contains(searchQuery, ignoreCase = true)

        val matchesFilter = when (currentFilter) {
            ResidentFilter.ALL -> true
            ResidentFilter.OUT_OF_ZONE -> !res.isInZone
            ResidentFilter.IN_ZONE -> res.isInZone
            ResidentFilter.LOW_BATTERY -> (res.lastBattery ?: 100) <= 25
        }

        matchesSearch && matchesFilter
    }

    fun launchGoogleMapsNavigation(resident: Resident) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SécuriRésident",
                                fontWeight = FontWeight.Black,
                                fontSize = 19.sp,
                                color = Color.White
                            )
                            Text(
                                text = "${facilityZone.name} • Rayon ${facilityZone.radiusMeters.toInt()}m",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SafeNavy,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshAllPositions() },
                        enabled = !isRefreshing,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("dashboard_refresh_button")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Actualiser toutes les balises",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    residentToEdit = null
                    showAddDialog = true
                },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(22.dp)) },
                text = { Text("Ajouter résident", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                containerColor = SafeNavy,
                contentColor = Color.White,
                modifier = Modifier.testTag("fab_add_resident")
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Emergency Alert Banner
            item {
                AlertBanner(
                    outOfZoneResidents = outOfZoneResidents,
                    isAlarmRinging = isAlarmRinging,
                    onSilenceAlarm = { viewModel.silenceAlarm() },
                    onNavigateToResident = { launchGoogleMapsNavigation(it) },
                    onViewOnMap = {
                        viewModel.selectResident(it)
                        onNavigateToMap(it)
                    }
                )
            }

            // Facility Surveillance Overview Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Surveillance de l'établissement",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Zone définie : cercle de ${facilityZone.radiusMeters.toInt()}m",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { showZoneEditor = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("btn_configure_zone")
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Régler", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                ElevatedButton(
                                    onClick = { onNavigateToMap(null) },
                                    colors = ButtonDefaults.elevatedButtonColors(
                                        containerColor = SafeNavy,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("btn_quick_open_map")
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Carte", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Status pills row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // En sécurité chip
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { currentFilter = ResidentFilter.IN_ZONE },
                                color = SafeGreen.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SafeGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "${inZoneResidents.size} en sécurité",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = SafeGreen
                                        )
                                        Text(
                                            text = "Dans l'enceinte",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Hors zone chip
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { currentFilter = ResidentFilter.OUT_OF_ZONE },
                                color = if (outOfZoneResidents.isEmpty()) Color(0xFFF1F5F9) else AlertRed.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (outOfZoneResidents.isEmpty()) Color.Gray else AlertRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "${outOfZoneResidents.size} hors zone",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (outOfZoneResidents.isEmpty()) Color.Gray else AlertRed
                                        )
                                        Text(
                                            text = if (outOfZoneResidents.isEmpty()) "Aucun incident" else "Alerte en cours",
                                            fontSize = 11.sp,
                                            color = if (outOfZoneResidents.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else AlertRed,
                                            fontWeight = if (outOfZoneResidents.isEmpty()) FontWeight.Normal else FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Quick Filter Chips Row (Ergonomic tabs)
            item {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            label = "Tous (${residents.size})",
                            isSelected = currentFilter == ResidentFilter.ALL,
                            onClick = { currentFilter = ResidentFilter.ALL }
                        )
                    }
                    if (outOfZoneResidents.isNotEmpty()) {
                        item {
                            FilterChip(
                                label = "🚨 Hors zone (${outOfZoneResidents.size})",
                                isSelected = currentFilter == ResidentFilter.OUT_OF_ZONE,
                                onClick = { currentFilter = ResidentFilter.OUT_OF_ZONE },
                                isAlert = true
                            )
                        }
                    }
                    item {
                        FilterChip(
                            label = "🟢 Dans l'établissement (${inZoneResidents.size})",
                            isSelected = currentFilter == ResidentFilter.IN_ZONE,
                            onClick = { currentFilter = ResidentFilter.IN_ZONE }
                        )
                    }
                    if (lowBatteryResidents.isNotEmpty()) {
                        item {
                            FilterChip(
                                label = "🔋 Batterie faible (${lowBatteryResidents.size})",
                                isSelected = currentFilter == ResidentFilter.LOW_BATTERY,
                                onClick = { currentFilter = ResidentFilter.LOW_BATTERY }
                            )
                        }
                    }
                }
            }

            // Optional search bar if multiple residents
            if (residents.size >= 4) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Rechercher un résident ou une chambre...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Resident Cards List
            if (filteredResidents.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (residents.isEmpty()) "Aucun résident enregistré" else "Aucun résultat pour ce filtre",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (residents.isEmpty())
                                    "Appuyez sur 'Ajouter résident' pour configurer les balises Weenect."
                                else
                                    "Modifiez votre recherche ou réinitialisez le filtre.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (residents.isEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { showAddDialog = true },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Ajouter un premier résident")
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredResidents, key = { it.id }) { res ->
                    ResidentCard(
                        resident = res,
                        onViewOnMap = {
                            viewModel.selectResident(res)
                            onNavigateToMap(res)
                        },
                        onNavigateTo = { launchGoogleMapsNavigation(res) },
                        onRefresh = { viewModel.refreshSingleResident(res) },
                        onRing = { viewModel.ringTracker(res) },
                        onVibrate = { viewModel.vibrateTracker(res) },
                        onSuperLive = { viewModel.activateSuperLive(res) },
                        onSimulateExit = { viewModel.simulateZoneExit(res) },
                        onSimulateReturn = { viewModel.simulateZoneReturn(res) },
                        onEdit = {
                            residentToEdit = res
                            showAddDialog = true
                        },
                        onDelete = { viewModel.deleteResident(res) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddResidentDialog(
            initialResident = residentToEdit,
            onDismiss = {
                showAddDialog = false
                residentToEdit = null
            },
            onSave = {
                viewModel.addOrUpdateResident(it)
                showAddDialog = false
                residentToEdit = null
            },
            onTestWeenect = { user, pass ->
                viewModel.testWeenectCredentials(user, pass)
            }
        )
    }

    if (showZoneEditor) {
        ZoneEditorDialog(
            currentZone = facilityZone,
            onDismiss = { showZoneEditor = false },
            onSave = {
                viewModel.updateFacilityZone(it)
                showZoneEditor = false
            },
            onUseCurrentLocation = {}
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    isAlert: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = when {
            isAlert && isSelected -> AlertRed
            isAlert -> AlertRed.copy(alpha = 0.15f)
            isSelected -> SafeNavy
            else -> MaterialTheme.colorScheme.surface
        },
        shadowElevation = if (isSelected) 3.dp else 1.dp,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isAlert && isSelected -> Color.White
                isAlert -> AlertRed
                isSelected -> Color.White
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}
