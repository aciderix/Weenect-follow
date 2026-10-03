package fr.alerteresidents.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.util.GeoUtils

/** Actions possibles sur un résident (partagées par les trois modes d'affichage). */
data class ResidentActions(
    val onOpen: (Resident) -> Unit,
    val onViewOnMap: (Resident) -> Unit,
    val onNavigateTo: (Resident) -> Unit,
    val onRefresh: (Resident) -> Unit,
    val onRing: (Resident) -> Unit,
    val onVibrate: (Resident) -> Unit,
    val onSuperLive: (Resident) -> Unit,
    val onStartOuting: (Resident) -> Unit,
    val onEndOuting: (Resident) -> Unit,
    val onDrill: (Resident) -> Unit,
    val onEdit: (Resident) -> Unit,
    val onDelete: (Resident) -> Unit,
    val onToggleSelect: (Resident) -> Unit
)

@Composable
private fun ResidentMenu(resident: Resident, status: ResidentStatus, actions: ResidentActions) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(48.dp).testTag("resident_menu_button_${resident.id}")
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options de ${resident.name}")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            @Composable
            fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color? = null, action: () -> Unit) =
                DropdownMenuItem(
                    text = { Text(label, color = color ?: Color.Unspecified) },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { menuExpanded = false; action() }
                )
            item("Actualiser la position", Icons.Default.Refresh) { actions.onRefresh(resident) }
            item("Faire vibrer la balise", Icons.Default.Vibration) { actions.onVibrate(resident) }
            item("SuperLive (suivi rapide)", Icons.Default.NotificationsActive) { actions.onSuperLive(resident) }
            if (status == ResidentStatus.PAUSED) {
                item("Terminer la sortie accompagnée", Icons.Default.CheckCircle) { actions.onEndOuting(resident) }
            } else if (resident.isTrackingActive) {
                item("Sortie accompagnée…", Icons.Default.DirectionsWalk) { actions.onStartOuting(resident) }
            }
            item("Sélectionner (actions groupées)", Icons.Default.CheckCircle) { actions.onToggleSelect(resident) }
            HorizontalDivider()
            item("Exercice : simuler une sortie", Icons.Default.School) { actions.onDrill(resident) }
            item("Modifier la fiche", Icons.Default.Edit) { actions.onEdit(resident) }
            item("Supprimer…", Icons.Default.Delete, AppStatusColors.danger) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ${resident.name} ?") },
            text = { Text("La fiche et le suivi de ce résident seront supprimés. L'historique des alertes est conservé.") },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; actions.onDelete(resident) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppStatusColors.dangerSolid, contentColor = Color.White)
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun StatusBand(resident: Resident, status: ResidentStatus, now: Long) {
    val style = statusStyle(status)
    Surface(color = style.container, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(style.icon, contentDescription = null, tint = style.onContainer, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(status.label.uppercase(), fontWeight = FontWeight.Black, fontSize = 14.sp, color = style.onContainer)
                Text(statusDetail(resident, status, now), fontSize = 13.sp, color = style.onContainer)
            }
            if (resident.hasPosition && status != ResidentStatus.INACTIVE) {
                Surface(color = style.solid, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        GeoUtils.formatDistance(resident.distanceFromCenterMeters),
                        fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** Vue détaillée : une carte par résident. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResidentCard(
    resident: Resident,
    status: ResidentStatus,
    now: Long,
    selected: Boolean,
    selectionMode: Boolean,
    actions: ResidentActions,
    modifier: Modifier = Modifier
) {
    val style = statusStyle(status)
    val isOutside = status == ResidentStatus.OUT
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(if (isOutside || selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else if (isOutside) style.accent else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = { if (selectionMode) actions.onToggleSelect(resident) else actions.onOpen(resident) },
                onLongClick = { actions.onToggleSelect(resident) }
            )
            .testTag("resident_card_${resident.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isOutside) 6.dp else 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { actions.onToggleSelect(resident) })
                }
                ResidentAvatar(resident, 52.dp, ringColor = style.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(resident.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        listOf(resident.roomNumber.ifBlank { "Chambre non définie" }, resident.unit).filter { it.isNotBlank() }.joinToString(" • "),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RiskBadge(resident.riskLevel, Modifier.padding(top = 2.dp))
                }
                if (resident.isTrackingActive) BatteryPill(resident.lastBattery, Modifier.padding(end = 2.dp))
                ResidentMenu(resident, status, actions)
            }
            Spacer(Modifier.height(10.dp))
            StatusBand(resident, status, now)

            if (resident.emergencyContact.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(resident.emergencyContact, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }

            if (status != ResidentStatus.INACTIVE) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { actions.onViewOnMap(resident) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("btn_view_map_${resident.id}"),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = resident.hasPosition
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Carte", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { actions.onRing(resident) },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("btn_ring_${resident.id}"),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = resident.hasTracker
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sonner", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    ElevatedButton(
                        onClick = { actions.onNavigateTo(resident) },
                        modifier = Modifier.weight(1.1f).height(48.dp).testTag("btn_navigate_${resident.id}"),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = resident.hasPosition,
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = if (isOutside) style.solid else MaterialTheme.colorScheme.primary,
                            contentColor = if (isOutside) Color.White else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Guider", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Vue compacte : une ligne par résident. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResidentRow(
    resident: Resident,
    status: ResidentStatus,
    now: Long,
    selected: Boolean,
    selectionMode: Boolean,
    actions: ResidentActions,
    modifier: Modifier = Modifier
) {
    val style = statusStyle(status)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .combinedClickable(
                onClick = { if (selectionMode) actions.onToggleSelect(resident) else actions.onOpen(resident) },
                onLongClick = { actions.onToggleSelect(resident) }
            )
            .testTag("resident_row_${resident.id}")
    ) {
        Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { actions.onToggleSelect(resident) })
            Box(Modifier.size(6.dp, 40.dp).padding(end = 0.dp)) {
                Surface(color = style.accent, shape = RoundedCornerShape(3.dp), modifier = Modifier.size(6.dp, 40.dp)) {}
            }
            Spacer(Modifier.width(8.dp))
            ResidentAvatar(resident, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resident.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (resident.roomNumber.isNotBlank()) {
                        Text("  ${resident.roomNumber}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text(
                    "${status.label} • ${statusDetail(resident, status, now)}",
                    fontSize = 13.sp, color = style.accent, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (ResidentStatusResolver.isLowBattery(resident)) BatteryPill(resident.lastBattery)
            ResidentMenu(resident, status, actions)
        }
    }
}

/** Vue grille : tuiles colorées, tout l'établissement d'un coup d'œil. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResidentTile(
    resident: Resident,
    status: ResidentStatus,
    now: Long,
    selected: Boolean,
    selectionMode: Boolean,
    actions: ResidentActions,
    modifier: Modifier = Modifier
) {
    val style = statusStyle(status)
    Surface(
        color = style.container,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .padding(4.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (selectionMode) actions.onToggleSelect(resident) else actions.onOpen(resident) },
                onLongClick = { actions.onToggleSelect(resident) }
            )
            .testTag("resident_tile_${resident.id}")
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ResidentAvatar(resident, 44.dp, ringColor = style.accent)
            Spacer(Modifier.height(4.dp))
            Text(resident.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = style.onContainer)
            Text(
                when (status) {
                    ResidentStatus.OUT -> formatDuration(now - (resident.exitedAt ?: now))
                    else -> status.label
                },
                fontSize = 12.sp, color = style.onContainer, maxLines = 1, fontWeight = FontWeight.SemiBold
            )
        }
    }
}
