package fr.alerteresidents.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.AlertEvent
import fr.alerteresidents.data.model.AlertType
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class JournalFilter(val label: String) {
    TO_ACK("À acquitter"),
    EXITS("Sorties / retours"),
    WARNINGS("Avertissements"),
    ACTIONS("Actions équipe"),
    DRILLS("Exercices"),
    ALL("Tout")
}

private fun JournalFilter.accepts(e: AlertEvent): Boolean = when (this) {
    JournalFilter.TO_ACK -> !e.isAcknowledged && !e.isDrill && e.alertType in AlertType.ACTIONABLE
    JournalFilter.EXITS -> !e.isDrill && (e.alertType == AlertType.EXIT_ZONE || e.alertType == AlertType.ENTER_ZONE)
    JournalFilter.WARNINGS -> e.alertType in setOf(AlertType.LOW_BATTERY, AlertType.TRACKER_OFFLINE, AlertType.SYNC_ERROR, AlertType.MONITORING_DEGRADED)
    JournalFilter.ACTIONS -> e.alertType in setOf(AlertType.HANDLING, AlertType.RESOLVED, AlertType.OUTING_START, AlertType.OUTING_END, AlertType.SHIFT_CHECK)
    JournalFilter.DRILLS -> e.isDrill
    JournalFilter.ALL -> true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(viewModel: ResidentViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val allAlerts by viewModel.allAlerts.collectAsState()
    val unacknowledged by viewModel.unacknowledgedAlerts.collectAsState()
    var filter by rememberSaveable { mutableStateOf(if (unacknowledged.isNotEmpty()) JournalFilter.TO_ACK else JournalFilter.ALL) }
    var menu by remember { mutableStateOf(false) }
    var confirmAckAll by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRENCH) }
    val shortFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH) }
    val visible = allAlerts.filter { filter.accepts(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal des alertes", fontWeight = FontWeight.Bold, color = Color.White) },
                actions = {
                    if (unacknowledged.isNotEmpty()) {
                        TextButton(onClick = { confirmAckAll = true }, modifier = Modifier.testTag("btn_ack_all_alerts")) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tout acquitter", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options", tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Exporter en PDF (affichage actuel)") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                onClick = { menu = false; viewModel.exportJournal(context, pdf = true, events = visible) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter en CSV / Excel (affichage actuel)") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                onClick = { menu = false; viewModel.exportJournal(context, pdf = false, events = visible) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Purger les événements de plus de 90 jours") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = { menu = false; confirmPurge = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(JournalFilter.entries.toList()) { f ->
                    val n = allAlerts.count { f.accepts(it) }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($n)", fontSize = 14.sp) })
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppStatusColors.safe, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (filter == JournalFilter.TO_ACK) "Rien à acquitter" else "Aucun événement", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { alert ->
                        JournalItem(alert, dateFormat, shortFormat, onAck = { viewModel.acknowledgeAlert(alert.id) })
                    }
                }
            }
        }
    }

    if (confirmAckAll) {
        AlertDialog(
            onDismissRequest = { confirmAckAll = false },
            title = { Text("Acquitter ${unacknowledged.size} alerte(s) ?") },
            text = { Text("Elles seront marquées comme vues par ${viewModel.staffName}. Les résidents encore hors zone restent en alerte.") },
            confirmButton = { Button(onClick = { viewModel.acknowledgeAllAlerts(); confirmAckAll = false }) { Text("Acquitter tout") } },
            dismissButton = { TextButton(onClick = { confirmAckAll = false }) { Text("Annuler") } }
        )
    }
    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Purger le journal ?") },
            text = { Text("Les événements de plus de 90 jours déjà acquittés seront supprimés. Pensez à exporter le journal avant.") },
            confirmButton = {
                Button(onClick = { viewModel.purgeJournal(90); confirmPurge = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AppStatusColors.dangerSolid, contentColor = Color.White)) { Text("Purger") }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun JournalItem(alert: AlertEvent, dateFormat: SimpleDateFormat, shortFormat: SimpleDateFormat, onAck: () -> Unit) {
    val c = AppStatusColors
    val (icon, color) = eventVisual(alert.alertType)
    val needsAck = !alert.isAcknowledged && alert.alertType in AlertType.ACTIONABLE
    Card(
        modifier = Modifier.fillMaxWidth().testTag("alert_item_${alert.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (needsAck && !alert.isDrill) c.dangerContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (needsAck) 3.dp else 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(alert.residentName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(AlertType.label(alert.alertType).uppercase(), fontWeight = FontWeight.Black, fontSize = 13.sp, color = color)
                        if (alert.isDrill) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = c.info.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                Text("EXERCICE", color = c.info, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                if (needsAck) {
                    OutlinedButton(onClick = onAck, modifier = Modifier.testTag("btn_ack_${alert.id}")) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Acquitter", fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val lines = buildList {
                add(dateFormat.format(Date(alert.timestamp)))
                if (alert.alertType == AlertType.EXIT_ZONE || alert.alertType == AlertType.ENTER_ZONE) add("Distance : ${alert.distanceMeters.toInt()} m")
                alert.details?.let { add(it) }
                if (alert.isAcknowledged && alert.acknowledgedBy != null && alert.alertType in AlertType.ACTIONABLE) {
                    add("Acquitté par ${alert.acknowledgedBy}" + (alert.acknowledgedAt?.let { " le ${shortFormat.format(Date(it))}" } ?: ""))
                }
            }
            lines.forEach { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun eventVisual(type: String): Pair<ImageVector, Color> {
    val c = AppStatusColors
    return when (type) {
        AlertType.EXIT_ZONE -> Icons.Default.Warning to c.danger
        AlertType.ENTER_ZONE, AlertType.RESOLVED -> Icons.Default.CheckCircle to c.safe
        AlertType.LOW_BATTERY -> Icons.Default.BatteryAlert to c.warning
        AlertType.TRACKER_OFFLINE -> Icons.Default.SignalWifiOff to c.warning
        AlertType.SYNC_ERROR, AlertType.MONITORING_DEGRADED -> Icons.Default.CloudOff to c.danger
        AlertType.HANDLING -> Icons.Default.PanTool to c.info
        AlertType.OUTING_START, AlertType.OUTING_END -> Icons.Default.DirectionsWalk to c.info
        AlertType.SHIFT_CHECK -> Icons.Default.FactCheck to c.neutral
        else -> Icons.Default.Info to c.neutral
    }
}
