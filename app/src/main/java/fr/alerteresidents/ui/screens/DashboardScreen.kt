package fr.alerteresidents.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.NavDestination
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.domain.HealthSnapshot
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.ui.components.AddResidentDialog
import fr.alerteresidents.ui.components.AlertBanner
import fr.alerteresidents.ui.components.OutingDialog
import fr.alerteresidents.ui.components.ResidentActions
import fr.alerteresidents.ui.components.ResidentCard
import fr.alerteresidents.ui.components.ResidentRow
import fr.alerteresidents.ui.components.ResidentTile
import fr.alerteresidents.ui.components.WeenectAccountsDialog
import fr.alerteresidents.ui.components.formatDuration
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.util.DashboardViewMode
import fr.alerteresidents.util.MapsNavigator

enum class ResidentFilter(val label: String) {
    ALL("Tous"),
    OUT_OF_ZONE("🚨 Hors zone"),
    UNRELIABLE("⚠️ Sans position fiable"),
    LOW_BATTERY("🔋 Batterie faible"),
    SAFE("🟢 En sécurité"),
    NOT_MONITORED("⏸ En pause / non suivis")
}

private fun matches(filter: ResidentFilter, status: ResidentStatus, r: Resident): Boolean = when (filter) {
    ResidentFilter.ALL -> true
    ResidentFilter.OUT_OF_ZONE -> status == ResidentStatus.OUT
    ResidentFilter.UNRELIABLE -> status == ResidentStatus.UNKNOWN || status == ResidentStatus.STALE
    ResidentFilter.LOW_BATTERY -> ResidentStatusResolver.isLowBattery(r)
    ResidentFilter.SAFE -> status == ResidentStatus.SAFE
    ResidentFilter.NOT_MONITORED -> status == ResidentStatus.PAUSED || status == ResidentStatus.INACTIVE
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
    val activeAlarms by viewModel.activeAlarms.collectAsState()
    val health by viewModel.health.collectAsState()
    val now by viewModel.now.collectAsState()
    val viewMode by viewModel.prefs.viewMode.collectAsState()
    val groupByUnit by viewModel.prefs.groupByUnit.collectAsState()
    val accounts by viewModel.accounts.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var residentToEdit by remember { mutableStateOf<Resident?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var outingFor by remember { mutableStateOf<List<Resident>?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentFilter by rememberSaveable { mutableStateOf(ResidentFilter.ALL) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var viewMenu by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    val staleMinutes = viewModel.prefs.staleMinutes
    val statuses = residents.associate { it.id to ResidentStatusResolver.resolve(it, now, staleMinutes) }
    val sorted = residents.sortedWith(ResidentStatusResolver.urgencyComparator(now, staleMinutes))
    val outOfZone = sorted.filter { statuses[it.id] == ResidentStatus.OUT }
    val counts = ResidentFilter.entries.associateWith { f -> residents.count { matches(f, statuses.getValue(it.id), it) } }

    val visible = sorted.filter { r ->
        val q = searchQuery.trim()
        val matchesSearch = q.isBlank() || r.name.contains(q, true) || r.roomNumber.contains(q, true) || r.unit.contains(q, true)
        matchesSearch && matches(currentFilter, statuses.getValue(r.id), r)
    }
    // Les résidents non surveillés sont regroupés en fin de liste
    val (monitored, notMonitored) = visible.partition {
        val s = statuses.getValue(it.id)
        s != ResidentStatus.PAUSED && s != ResidentStatus.INACTIVE
    }

    val actions = ResidentActions(
        onOpen = { viewModel.selectResident(it.id); onNavigateToMap(it) },
        onViewOnMap = { viewModel.selectResident(it.id); onNavigateToMap(it) },
        onNavigateTo = { r -> r.lastLatitude?.let { lat -> MapsNavigator.navigate(context, lat, r.lastLongitude!!) } },
        onRefresh = { viewModel.refreshSingleResident(it) },
        onRing = { viewModel.ringTracker(it) },
        onVibrate = { viewModel.vibrateTracker(it) },
        onSuperLive = { viewModel.activateSuperLive(it) },
        onStartOuting = { outingFor = listOf(it) },
        onEndOuting = { viewModel.endOuting(it.id) },
        onDrill = { viewModel.simulateZoneExit(it) },
        onEdit = { residentToEdit = it; showAddDialog = true },
        onDelete = { viewModel.deleteResident(it) },
        onToggleSelect = { r -> selectedIds = if (r.id in selectedIds) selectedIds - r.id else selectedIds + r.id }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(facilityZone.name, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Color.White, maxLines = 1)
                            Text(
                                if (facilityZone.isPolygon) "Zone tracée (${facilityZone.getPolygonPoints().size} points)"
                                else "Zone circulaire • ${facilityZone.radiusMeters.toInt()} m",
                                fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy, actionIconContentColor = Color.White),
                actions = {
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { viewMenu = true }, modifier = Modifier.testTag("dashboard_view_mode")) {
                            Icon(
                                when (viewMode) {
                                    DashboardViewMode.DETAILED -> Icons.Default.ViewAgenda
                                    DashboardViewMode.COMPACT -> Icons.Default.ViewList
                                    DashboardViewMode.GRID -> Icons.Default.Apps
                                },
                                contentDescription = "Mode d'affichage"
                            )
                        }
                        DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                            listOf(
                                Triple(DashboardViewMode.DETAILED, "Cartes détaillées", Icons.Default.ViewAgenda),
                                Triple(DashboardViewMode.COMPACT, "Liste compacte", Icons.Default.ViewList),
                                Triple(DashboardViewMode.GRID, "Grille (vue d'ensemble)", Icons.Default.Apps)
                            ).forEach { (mode, label, icon) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (mode == viewMode) FontWeight.Bold else FontWeight.Normal) },
                                    leadingIcon = { Icon(icon, contentDescription = null) },
                                    onClick = { viewModel.setViewMode(mode); viewMenu = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Regrouper par unité") },
                                leadingIcon = { Checkbox(checked = groupByUnit, onCheckedChange = null) },
                                onClick = { viewModel.setGroupByUnit(!groupByUnit); viewMenu = false }
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refreshAllPositions() },
                        enabled = !isRefreshing,
                        modifier = Modifier.size(48.dp).testTag("dashboard_refresh_button")
                    ) {
                        if (isRefreshing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Actualiser toutes les balises")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { residentToEdit = null; showAddDialog = true },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Ajouter résident", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                    containerColor = SafeNavy,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("fab_add_resident")
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                HealthHeader(
                    health = health,
                    now = now,
                    outCount = outOfZone.size,
                    unreliableCount = counts.getValue(ResidentFilter.UNRELIABLE),
                    safeCount = counts.getValue(ResidentFilter.SAFE),
                    pausedCount = counts.getValue(ResidentFilter.NOT_MONITORED),
                    onClick = { viewModel.navigateTo(NavDestination.STATUS) }
                )
            }
            item {
                AlertBanner(
                    outOfZoneResidents = outOfZone,
                    ringingIds = activeAlarms.keys,
                    now = now,
                    onSilenceAll = { viewModel.silenceAlarm() },
                    onHandle = { viewModel.handleAlert(it.id) },
                    onFound = { viewModel.markFound(it.id) },
                    onNavigateToResident = { r -> r.lastLatitude?.let { MapsNavigator.navigate(context, it, r.lastLongitude!!) } },
                    onViewOnMap = { viewModel.selectResident(it.id); onNavigateToMap(it) }
                )
            }

            if (selectionMode) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedIds = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Annuler la sélection") }
                            Text("${selectedIds.size} sélectionné(s)", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { selectedIds = visible.map { it.id }.toSet() }) { Text("Tout") }
                            Button(onClick = { outingFor = residents.filter { it.id in selectedIds } }) {
                                Icon(Icons.Default.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sortie")
                            }
                        }
                    }
                }
            }

            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ResidentFilter.entries.toList()) { f ->
                        val count = counts.getValue(f)
                        val alert = f == ResidentFilter.OUT_OF_ZONE && count > 0
                        FilterChip(
                            selected = currentFilter == f,
                            onClick = { currentFilter = f },
                            label = { Text("${f.label} ($count)", fontSize = 14.sp, fontWeight = if (alert) FontWeight.Bold else FontWeight.Normal) },
                            colors = if (alert) FilterChipDefaults.filterChipColors(
                                containerColor = AppStatusColors.dangerContainer,
                                labelColor = AppStatusColors.onDangerContainer,
                                selectedContainerColor = AppStatusColors.dangerSolid,
                                selectedLabelColor = Color.White
                            ) else FilterChipDefaults.filterChipColors(),
                            modifier = Modifier.testTag("filter_${f.name}")
                        )
                    }
                }
            }

            if (residents.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Rechercher : nom, chambre, unité…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Effacer la recherche")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("dashboard_search")
                    )
                }
            }

            if (visible.isEmpty()) {
                item { EmptyState(residents.isEmpty(), hasAccounts = accounts.isNotEmpty(), onAdd = { showAddDialog = true }, onAccounts = { showAccounts = true }) }
            }

            fun section(list: List<Resident>, title: String?) {
                if (list.isEmpty()) return
                val groups: List<Pair<String?, List<Resident>>> =
                    if (groupByUnit) list.groupBy { it.unit.ifBlank { "Sans unité" } }.toSortedMap().map { it.key to it.value }
                    else listOf(null to list)
                if (title != null) item(key = "header_$title") { SectionHeader(title, list.size) }
                for ((unit, members) in groups) {
                    if (unit != null) item(key = "unit_${title}_$unit") { SectionHeader(unit, members.size, small = true) }
                    when (viewMode) {
                        DashboardViewMode.DETAILED -> items(members, key = { it.id }) { r ->
                            ResidentCard(r, statuses.getValue(r.id), now, r.id in selectedIds, selectionMode, actions)
                        }
                        DashboardViewMode.COMPACT -> items(members, key = { it.id }) { r ->
                            ResidentRow(r, statuses.getValue(r.id), now, r.id in selectedIds, selectionMode, actions)
                        }
                        DashboardViewMode.GRID -> item(key = "grid_${title}_$unit") {
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                val columns = (maxWidth / 110.dp).toInt().coerceAtLeast(3)
                                Column {
                                    members.chunked(columns).forEach { rowItems ->
                                        Row(Modifier.fillMaxWidth()) {
                                            rowItems.forEach { r ->
                                                ResidentTile(r, statuses.getValue(r.id), now, r.id in selectedIds, selectionMode, actions, Modifier.weight(1f))
                                            }
                                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            section(monitored, if (notMonitored.isNotEmpty() && monitored.isNotEmpty()) "Surveillés" else null)
            section(notMonitored, "En pause / suivi désactivé")
        }
    }

    if (showAddDialog) {
        AddResidentDialog(
            initialResident = residentToEdit,
            accounts = accounts,
            viewModel = viewModel,
            onDismiss = { showAddDialog = false; residentToEdit = null },
            onSave = { profile, previousPhoto ->
                viewModel.saveResident(profile, previousPhoto)
                showAddDialog = false
                residentToEdit = null
            },
            onManageAccounts = { showAccounts = true }
        )
    }
    if (showAccounts) {
        WeenectAccountsDialog(viewModel = viewModel, onDismiss = { showAccounts = false })
    }
    outingFor?.let { list ->
        OutingDialog(
            residentNames = list.map { it.name },
            onDismiss = { outingFor = null },
            onConfirm = { minutes, reason ->
                viewModel.startOuting(list.map { it.id }, minutes, reason)
                outingFor = null
                selectedIds = emptySet()
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, small: Boolean = false) {
    Text(
        "$title ($count)",
        fontWeight = FontWeight.Bold,
        fontSize = if (small) 14.sp else 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = if (small) 6.dp else 14.dp, bottom = 4.dp)
    )
}

/** En-tête « état de la surveillance » : reflète l'état réel du service, pas un texte fixe. */
@Composable
private fun HealthHeader(
    health: HealthSnapshot,
    now: Long,
    outCount: Int,
    unreliableCount: Int,
    safeCount: Int,
    pausedCount: Int,
    onClick: () -> Unit
) {
    val c = AppStatusColors
    val degraded = health.isDegraded(now)
    val (color, container, icon, title) = when {
        degraded -> Quad(c.danger, c.dangerContainer, Icons.Default.Error, "Surveillance dégradée")
        health.errorCount > 0 -> Quad(c.warning, c.warningContainer, Icons.Default.Warning, "Surveillance partielle : ${health.errorCount} balise(s) en erreur")
        else -> Quad(c.safe, c.safeContainer, Icons.Default.CheckCircle, "Surveillance active" + if (health.nightMode) " (mode nuit)" else "")
    }
    val lastCycleAt = health.lastCycleAt
    val subtitle = when {
        !health.serviceRunning -> "Le service de surveillance ne tourne pas. Touchez pour diagnostiquer."
        lastCycleAt == null -> "Première vérification en cours…"
        degraded && health.consecutiveFailedCycles > 0 -> "Aucune balise joignable depuis ${health.consecutiveFailedCycles} cycles. Touchez pour diagnostiquer."
        else -> "Dernière vérification il y a ${formatDuration(now - lastCycleAt)} • toutes les ${health.nextIntervalSeconds} s"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onClick).testTag("health_header"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
                    Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                CountPill("$outCount hors zone", if (outCount > 0) c.danger else c.neutral, Modifier.weight(1f))
                CountPill("$unreliableCount à vérifier", if (unreliableCount > 0) c.warning else c.neutral, Modifier.weight(1f))
                CountPill("$safeCount en sécurité", c.safe, Modifier.weight(1f))
                if (pausedCount > 0) CountPill("$pausedCount en pause", c.info, Modifier.weight(0.9f))
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun CountPill(text: String, color: Color, modifier: Modifier) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
    }
}

@Composable
private fun EmptyState(noResidents: Boolean, hasAccounts: Boolean, onAdd: () -> Unit, onAccounts: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (noResidents) Icons.Default.PersonAdd else Icons.Default.Workspaces, contentDescription = null,
                modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(if (noResidents) "Aucun résident enregistré" else "Aucun résultat pour ce filtre", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !noResidents -> "Modifiez votre recherche ou choisissez « Tous »."
                    !hasAccounts -> "Commencez par ajouter le compte Weenect de l'établissement, puis les résidents."
                    else -> "Ajoutez un résident et associez-lui sa balise."
                },
                fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (noResidents) {
                Spacer(Modifier.height(14.dp))
                if (!hasAccounts) Button(onClick = onAccounts) { Text("Ajouter un compte Weenect") }
                else Button(onClick = onAdd) { Text("Ajouter un premier résident") }
            }
        }
    }
}
