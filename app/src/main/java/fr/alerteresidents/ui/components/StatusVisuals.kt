package fr.alerteresidents.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.util.AppPreferences
import fr.alerteresidents.util.PhotoStore

/** [accent] : texte/icônes ; [solid] : fonds pleins avec texte blanc. */
data class StatusStyle(val accent: Color, val container: Color, val onContainer: Color, val icon: ImageVector, val solid: Color)

@Composable
fun statusStyle(status: ResidentStatus): StatusStyle {
    val c = AppStatusColors
    return when (status) {
        ResidentStatus.OUT -> StatusStyle(c.danger, c.dangerContainer, c.onDangerContainer, Icons.Default.Warning, c.dangerSolid)
        ResidentStatus.UNKNOWN -> StatusStyle(c.warning, c.warningContainer, c.onWarningContainer, Icons.Default.HelpOutline, c.warningSolid)
        ResidentStatus.STALE -> StatusStyle(c.warning, c.warningContainer, c.onWarningContainer, Icons.Default.SignalWifiOff, c.warningSolid)
        ResidentStatus.SAFE -> StatusStyle(c.safe, c.safeContainer, c.onSafeContainer, Icons.Default.CheckCircle, c.safeSolid)
        ResidentStatus.PAUSED -> StatusStyle(c.info, c.infoContainer, c.onInfoContainer, Icons.Default.DirectionsWalk, c.infoSolid)
        ResidentStatus.INACTIVE -> StatusStyle(c.neutral, c.neutralContainer, c.onNeutralContainer, Icons.Default.PauseCircle, c.neutralSolid)
    }
}

/** « 12 min », « 1 h 05 », « 2 j » */
fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "moins d'1 min"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
        else -> "${minutes / (24 * 60)} j"
    }
}

fun formatClock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ms))

/** Phrase de détail du statut, pour la carte ou la ligne compacte. */
fun statusDetail(r: Resident, status: ResidentStatus, now: Long): String = when (status) {
    ResidentStatus.OUT -> buildString {
        append("Sorti(e) depuis ${formatDuration(now - (r.exitedAt ?: now))}")
        when (r.alertState) {
            AlertState.HANDLING -> append(" • pris en charge par ${r.alertHandledBy}")
            AlertState.RESOLVED -> append(" • retrouvé(e) par ${r.alertHandledBy}, attente balise")
        }
    }
    ResidentStatus.UNKNOWN -> ResidentStatusResolver.unknownReason(r)
    ResidentStatus.STALE -> r.lastUpdatedTime?.let { "Dernier signal il y a ${formatDuration(now - it)}" + if (r.isInDeepSleep) " (veille)" else "" }
        ?: "Heure du dernier signal inconnue"
    ResidentStatus.SAFE -> "Signal il y a ${formatDuration(now - (r.lastUpdatedTime ?: now))}"
    ResidentStatus.PAUSED -> "Sortie accompagnée jusqu'à ${formatClock(r.pausedUntil ?: now)}" + (r.pauseReason?.let { " • $it" } ?: "")
    ResidentStatus.INACTIVE -> "Suivi désactivé"
}

@Composable
fun ResidentAvatar(resident: Resident, size: Dp = 48.dp, ringColor: Color? = null, modifier: Modifier = Modifier) {
    val bg = remember(resident.avatarColorHex) {
        runCatching { Color(android.graphics.Color.parseColor(resident.avatarColorHex)) }.getOrDefault(SafeNavy)
    }
    val photo = remember(resident.photoUri) { PhotoStore.load(resident.photoUri)?.asImageBitmap() }
    val base = modifier
        .size(size)
        .clip(CircleShape)
        .let { m -> if (ringColor != null) m.border(3.dp, ringColor, CircleShape) else m }
    if (photo != null) {
        Image(bitmap = photo, contentDescription = "Photo de ${resident.name}", contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(bg), contentAlignment = Alignment.Center) {
            Text(resident.initials, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.36f).sp)
        }
    }
}

@Composable
fun BatteryPill(battery: Int?, modifier: Modifier = Modifier) {
    val c = AppStatusColors
    val color = when {
        battery == null -> c.neutral
        battery <= AppPreferences.LOW_BATTERY_THRESHOLD -> c.danger
        battery <= 40 -> c.warning
        else -> c.safe
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (battery == null || battery <= AppPreferences.LOW_BATTERY_THRESHOLD) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                contentDescription = "Batterie", tint = color, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(if (battery != null) "$battery%" else "--", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun RiskBadge(level: Int, modifier: Modifier = Modifier) {
    if (level <= 0) return
    val c = AppStatusColors
    val (label, color) = if (level >= 2) "Risque élevé" to c.danger else "Vigilance" to c.warning
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

fun riskLabel(level: Int) = when (level) {
    2 -> "Risque élevé de fugue"
    1 -> "Vigilance"
    else -> "Standard"
}
