package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Resident
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.OnAlertRedContainer
import com.example.ui.theme.OnSafeGreenContainer
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeGreenContainer
import com.example.ui.theme.SafeNavy
import com.example.ui.theme.WarningAmber
import com.example.util.GeoUtils

@Composable
fun ResidentCard(
    resident: Resident,
    onViewOnMap: (Resident) -> Unit,
    onNavigateTo: (Resident) -> Unit,
    onRefresh: (Resident) -> Unit,
    onRing: (Resident) -> Unit,
    onVibrate: (Resident) -> Unit,
    onSuperLive: (Resident) -> Unit,
    onSimulateExit: (Resident) -> Unit,
    onSimulateReturn: (Resident) -> Unit,
    onEdit: (Resident) -> Unit,
    onDelete: (Resident) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val isOutside = !resident.isInZone
    val cardBg = if (isOutside) Color(0xFFFFF5F5) else MaterialTheme.colorScheme.surface
    val borderStrokeColor = if (isOutside) AlertRed else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val cardElevation = if (isOutside) 6.dp else 2.dp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(
                width = if (isOutside) 2.dp else 1.dp,
                color = borderStrokeColor,
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("resident_card_${resident.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Avatar, Identity, Battery, Menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar Circle
                val parsedColor = try {
                    Color(android.graphics.Color.parseColor(resident.avatarColorHex))
                } catch (_: Exception) {
                    SafeNavy
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(parsedColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resident.name.trim().take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Name and Room (Wrap properly, never truncated)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resident.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (resident.roomNumber.isNotEmpty()) resident.roomNumber else "Chambre non définie",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Battery Pill
                val battery = resident.lastBattery ?: 100
                val batteryColor = when {
                    battery <= 20 -> AlertRed
                    battery <= 45 -> WarningAmber
                    else -> SafeGreen
                }
                Surface(
                    color = batteryColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (battery <= 20) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                            contentDescription = "Batterie",
                            tint = batteryColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$battery%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = batteryColor
                        )
                    }
                }

                // Options Menu (Min touch target 48dp)
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("resident_menu_button_${resident.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options supplémentaires",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Actualiser la balise") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRefresh(resident)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Faire vibrer la balise") },
                            leadingIcon = { Icon(Icons.Default.Vibration, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onVibrate(resident)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("SuperLive 1s (urgence)") },
                            leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSuperLive(resident)
                            }
                        )
                        if (resident.isInZone) {
                            DropdownMenuItem(
                                text = { Text("Simuler sortie (Test Alerte)") },
                                leadingIcon = { Icon(Icons.Default.Warning, tint = AlertRed, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSimulateExit(resident)
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Simuler retour zone") },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, tint = SafeGreen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSimulateReturn(resident)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Modifier la fiche") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit(resident)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer", color = AlertRed) },
                            leadingIcon = { Icon(Icons.Default.Delete, tint = AlertRed, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete(resident)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Prominent Status Banner
            val dist = resident.distanceFromCenterMeters
            val distStr = GeoUtils.formatDistance(dist)
            val timeStr = GeoUtils.formatTimeAgo(resident.lastUpdatedTime)

            Surface(
                color = if (isOutside) AlertRedContainer else SafeGreenContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
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
                            tint = if (isOutside) OnAlertRedContainer else OnSafeGreenContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isOutside) "HORS ZONE DE SÉCURITÉ" else "Dans l'enceinte de l'établissement",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                color = if (isOutside) OnAlertRedContainer else OnSafeGreenContainer
                            )
                            Text(
                                text = "Signal reçu : $timeStr",
                                fontSize = 11.sp,
                                color = (if (isOutside) OnAlertRedContainer else OnSafeGreenContainer).copy(alpha = 0.85f)
                            )
                        }
                    }

                    // Distance badge
                    Surface(
                        color = if (isOutside) AlertRed else SafeGreen,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = distStr,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Optional Emergency Contact Line
            if (resident.emergencyContact.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = resident.emergencyContact,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Bar: Ergonomic 3-button layout with full text readability
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Button 1: Voir sur la carte
                OutlinedButton(
                    onClick = { onViewOnMap(resident) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_view_map_${resident.id}"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Carte",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Button 2: Faire sonner le boîtier (direct vital action!)
                FilledTonalButton(
                    onClick = { onRing(resident) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_ring_${resident.id}"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Sonner",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Button 3: Guidage GPS vers le résident
                ElevatedButton(
                    onClick = { onNavigateTo(resident) },
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp)
                        .testTag("btn_navigate_${resident.id}"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = if (isOutside) AlertRed else SafeNavy,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Guider",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
