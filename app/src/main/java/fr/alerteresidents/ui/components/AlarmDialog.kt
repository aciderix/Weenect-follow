package fr.alerteresidents.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.util.AlarmInfo
import fr.alerteresidents.util.GeoUtils

/**
 * Pop-up affichée tant qu'au moins une alarme sonne (réelle ou exercice). Liste TOUS les
 * résidents en alarme, chacun avec ses propres boutons.
 */
@Composable
fun AlarmDialog(
    alarms: List<AlarmInfo>,
    residentsById: Map<Long, Resident>,
    now: Long,
    onHandle: (Long) -> Unit,
    onSilence: (Long) -> Unit,
    onSilenceAll: () -> Unit,
    onNavigate: (Resident) -> Unit
) {
    val danger = AppStatusColors.danger
    val allDrill = alarms.all { it.isDrill }
    AlertDialog(
        onDismissRequest = { /* une action explicite est requise */ },
        icon = {
            Box(Modifier.size(54.dp).background(danger.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, contentDescription = "Urgence", tint = danger, modifier = Modifier.size(36.dp))
            }
        },
        title = {
            Text(
                (if (allDrill) "EXERCICE — " else "") +
                    if (alarms.size == 1) "🚨 SORTIE DE ZONE" else "🚨 ${alarms.size} SORTIES DE ZONE",
                fontWeight = FontWeight.Black, fontSize = 20.sp, color = danger
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                alarms.sortedBy { it.startedAt }.forEach { alarm ->
                    val r = residentsById[alarm.residentId]
                    Surface(color = danger.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r != null) ResidentAvatar(r, 44.dp, ringColor = danger)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (alarm.isDrill) "[EXERCICE] " else "") + (if (alarm.isReminder) "[RAPPEL] " else "") + alarm.residentName,
                                        fontWeight = FontWeight.Bold, fontSize = 17.sp
                                    )
                                    if (r != null) {
                                        Text(
                                            buildString {
                                                append(r.roomNumber.ifBlank { "Chambre non renseignée" })
                                                if (r.hasPosition) append(" • ${GeoUtils.formatDistance(r.distanceFromCenterMeters)}")
                                                r.exitedAt?.let { append(" • depuis ${formatDuration(now - it)}") }
                                            },
                                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    alarm.reportedBy?.let {
                                        Text("Signalé par : $it", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { onHandle(alarm.residentId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppStatusColors.dangerSolid, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.weight(1.3f).heightIn(min = 48.dp).testTag("btn_alarm_handle_${alarm.residentId}")
                                ) {
                                    Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Je m'en occupe", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                                }
                                if (r != null && r.hasPosition) {
                                    OutlinedButton(
                                        onClick = { onHandle(alarm.residentId); onNavigate(r) },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("btn_alarm_popup_guide_${alarm.residentId}")
                                    ) {
                                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Guider", fontSize = 14.sp, maxLines = 1)
                                    }
                                }
                            }
                            TextButton(onClick = { onSilence(alarm.residentId) }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Couper le son de cette alarme", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (alarms.size > 1) {
                OutlinedButton(onClick = onSilenceAll, modifier = Modifier.testTag("btn_alarm_popup_silence")) {
                    Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Couper toutes les sonneries", fontSize = 14.sp)
                }
            } else {
                OutlinedButton(onClick = onSilenceAll, modifier = Modifier.testTag("btn_alarm_popup_silence")) {
                    Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Couper la sonnerie", fontSize = 14.sp)
                }
            }
        }
    )
}
