package fr.alerteresidents.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.util.GeoUtils

/**
 * Bandeau des sorties en cours, trié par heure de sortie (la plus ancienne en premier).
 * Repliable, mais jamais « fermable » : une alerte ne disparaît que si la balise confirme
 * le retour, ou si un soignant indique « Je m'en occupe » / « Retrouvé ».
 */
@Composable
fun AlertBanner(
    outOfZoneResidents: List<Resident>,
    ringingIds: Set<Long>,
    now: Long,
    onSilenceAll: () -> Unit,
    onHandle: (Resident) -> Unit,
    onFound: (Resident) -> Unit,
    onNavigateToResident: (Resident) -> Unit,
    onViewOnMap: (Resident) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val sorted = outOfZoneResidents.sortedBy { it.exitedAt ?: Long.MAX_VALUE }
    val danger = AppStatusColors.dangerSolid

    AnimatedVisibility(visible = sorted.isNotEmpty(), enter = expandVertically(), exit = shrinkVertically()) {
        Card(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("emergency_alert_banner"),
            colors = CardDefaults.cardColors(containerColor = danger, contentColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("alert_banner_header")
                ) {
                    Icon(
                        if (ringingIds.isNotEmpty()) Icons.Default.NotificationsActive else Icons.Default.Warning,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (sorted.size == 1) "1 RÉSIDENT HORS ZONE" else "${sorted.size} RÉSIDENTS HORS ZONE",
                            fontWeight = FontWeight.Black, fontSize = 16.sp
                        )
                        val waiting = sorted.count { it.alertState == AlertState.ACTIVE }
                        Text(
                            if (waiting > 0) "$waiting sans prise en charge" else "Tous pris en charge",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    if (ringingIds.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = onSilenceAll,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = danger),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 40.dp).testTag("silence_alarm_button")
                        ) {
                            Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Couper tout", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                        tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(28.dp)
                    )
                }

                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    sorted.forEach { resident ->
                        AlertRow(resident, resident.id in ringingIds, now, onHandle, onFound, onNavigateToResident, onViewOnMap)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(
    resident: Resident,
    ringing: Boolean,
    now: Long,
    onHandle: (Resident) -> Unit,
    onFound: (Resident) -> Unit,
    onNavigate: (Resident) -> Unit,
    onMap: (Resident) -> Unit
) {
    val danger = AppStatusColors.dangerSolid
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResidentAvatar(resident, 40.dp, ringColor = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    resident.name + if (ringing) "  🔔" else "",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Sorti(e) depuis ${formatDuration(now - (resident.exitedAt ?: now))} • " +
                        "${GeoUtils.formatDistance(resident.distanceFromCenterMeters)} • ${resident.roomNumber.ifBlank { "—" }}",
                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.92f)
                )
                when (resident.alertState) {
                    AlertState.HANDLING -> Text("✋ Pris en charge par ${resident.alertHandledBy}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    AlertState.RESOLVED -> Text("✅ Retrouvé(e) par ${resident.alertHandledBy} — attente balise", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    else -> Text("⏳ Personne n'a encore pris l'alerte", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (resident.alertState == AlertState.ACTIVE) {
                BannerButton("Je m'en occupe", Icons.Default.PanTool, Color.White, danger, Modifier.weight(1.4f).testTag("alert_handle_${resident.id}")) { onHandle(resident) }
            } else if (resident.alertState == AlertState.HANDLING) {
                BannerButton("Retrouvé", null, Color.White, AppStatusColors.safeSolid, Modifier.weight(1.4f).testTag("alert_resolve_${resident.id}")) { onFound(resident) }
            }
            BannerButton("Guider", Icons.Default.Navigation, Color(0xFF0F172A), Color.White, Modifier.weight(1f).testTag("alert_navigate_${resident.id}"),
                enabled = resident.hasPosition) { onNavigate(resident) }
            BannerButton("Carte", Icons.Default.Map, Color.White.copy(alpha = 0.18f), Color.White, Modifier.weight(0.9f).testTag("alert_view_map_${resident.id}")) { onMap(resident) }
        }
    }
}

@Composable
private fun BannerButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    container: Color,
    content: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
