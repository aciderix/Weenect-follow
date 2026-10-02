package fr.alerteresidents.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.domain.ResidentStatus
import fr.alerteresidents.domain.ResidentStatusResolver
import fr.alerteresidents.ui.components.ResidentAvatar
import fr.alerteresidents.ui.components.formatDuration
import fr.alerteresidents.ui.components.statusDetail
import fr.alerteresidents.ui.components.statusStyle
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.desktop.platform.Autostart
import fr.alerteresidents.ui.components.CloudDevicesList
import fr.alerteresidents.ui.components.CloudStatusLine
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.util.AppPreferences

data class PermissionCheck(val label: String, val ok: Boolean, val help: String, val fix: (() -> Unit)?)

/** Vérifications du poste Windows. */
fun pcChecks(viewModel: ResidentViewModel): List<PermissionCheck> {
    val online = runCatching { java.net.InetAddress.getByName("apiv4.weenect.com"); true }.getOrDefault(false)
    return listOf(
        PermissionCheck(
            "Démarrage automatique avec Windows", Autostart.isEnabled(),
            if (Autostart.isSupported) "Sans lui, la surveillance s'arrête après un redémarrage du PC."
            else "Disponible uniquement avec la version installée de l'application.",
            if (Autostart.isSupported) ({ Autostart.setEnabled(true); Unit }) else null
        ),
        PermissionCheck(
            "Notifications Windows", java.awt.SystemTray.isSupported(),
            "La zone de notification de Windows n'est pas disponible.", null
        ),
        PermissionCheck(
            "Volume forcé au maximum pendant une alarme", viewModel.prefs.forceMaxVolume.value,
            "Activez-le dans Paramètres › Son de l'alarme.", { viewModel.prefs.setForceMaxVolume(true) }
        ),
        PermissionCheck("Connexion internet", online, "La position des balises est lue sur le serveur Weenect.", null),
        PermissionCheck("Mise en veille empêchée pendant la surveillance", true, "", null)
    )
}

/** État réel de la surveillance + check-list de prise de poste. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringStatusScreen(viewModel: ResidentViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val health by viewModel.health.collectAsState()
    val residents by viewModel.residents.collectAsState()
    val now by viewModel.now.collectAsState()
    val staffName by viewModel.prefs.staffName.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    val checks = remember(refreshKey, now) { pcChecks(viewModel) }
    var alarmTested by remember { mutableStateOf(false) }
    var nameDraft by remember(staffName) { mutableStateOf(staffName) }

    val staleMinutes = viewModel.prefs.staleMinutes
    val sorted = residents.sortedWith(ResidentStatusResolver.urgencyComparator(now, staleMinutes))
    val statuses = sorted.associate { it.id to ResidentStatusResolver.resolve(it, now, staleMinutes) }
    val problems = sorted.filter {
        statuses[it.id] in setOf(ResidentStatus.OUT, ResidentStatus.UNKNOWN, ResidentStatus.STALE) || ResidentStatusResolver.isLowBattery(it)
    }
    val c = AppStatusColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("État de la surveillance", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 880.dp).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Service de surveillance") {
                val degraded = health.isDegraded(now)
                CheckLine(if (degraded) "Surveillance dégradée" else "Surveillance active", !degraded)
                Text(
                    buildString {
                        append(if (health.serviceRunning) "Service en cours d'exécution" else "Service arrêté")
                        health.lastCycleAt?.let { append(" • dernier cycle il y a ${formatDuration(now - it)}") }
                        append(" • intervalle ${health.nextIntervalSeconds} s")
                        if (health.nightMode) append(" • mode nuit")
                    },
                    fontSize = 14.sp
                )
                Text("Dernier cycle : ${health.okCount} balise(s) OK, ${health.errorCount} en erreur", fontSize = 14.sp)
                if (!health.serviceRunning || degraded) {
                    OutlinedButton(onClick = { viewModel.restartMonitoringService() }) { Text("Relancer la surveillance") }
                }
            }

            SectionCard("Réglages du poste Windows") {
                checks.forEach { check ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CheckLine(check.label, check.ok)
                            if (!check.ok) Text(check.help, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!check.ok && check.fix != null) TextButton(onClick = { check.fix.invoke(); refreshKey++ }) { Text("Corriger") }
                    }
                }
            }

            val cloud by viewModel.cloud.collectAsState()
            SectionCard("Partage entre appareils") {
                CloudStatusLine(cloud, now)
                if (cloud.configured && cloud.devices.isNotEmpty()) CloudDevicesList(cloud, now)
                if (!cloud.configured) Text("À configurer dans Paramètres › Partage entre appareils.", fontSize = 13.sp)
            }

            SectionCard("Balises (${residents.count { it.isTrackingActive }} suivies)") {
                if (problems.isEmpty()) {
                    CheckLine("Toutes les balises répondent", true)
                }
                sorted.forEach { r ->
                    val status = statuses.getValue(r.id)
                    val style = statusStyle(status)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        ResidentAvatar(r, 32.dp, ringColor = style.accent)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${status.label} • ${statusDetail(r, status, now)}", fontSize = 13.sp, color = style.accent)
                        }
                        Text(r.lastBattery?.let { "$it %" } ?: "--", fontSize = 14.sp,
                            color = if ((r.lastBattery ?: 100) <= AppPreferences.LOW_BATTERY_THRESHOLD) c.danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionCard("Prise de poste") {
                val last = viewModel.prefs.lastShiftCheck
                Text(
                    if (last > 0) "Dernière prise de poste il y a ${formatDuration(now - last)}" else "Aucune prise de poste enregistrée",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Votre nom (soignant de garde)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { CheckLine("Sonnerie d'alarme testée", alarmTested) }
                    TextButton(onClick = { viewModel.triggerManualLoudAlarmTest(); alarmTested = true }) { Text("Tester") }
                }
                CheckLine("Réglages du poste OK", checks.all { it.ok })
                CheckLine(
                    if (problems.isEmpty()) "Toutes les balises OK" else "${problems.size} balise(s) à vérifier : ${problems.joinToString { it.name }}",
                    problems.isEmpty()
                )
                Button(
                    enabled = nameDraft.isNotBlank() && alarmTested,
                    onClick = {
                        viewModel.setStaffName(nameDraft)
                        viewModel.validateShiftCheck(
                            "Sonnerie testée ; réglages ${if (checks.all { it.ok }) "OK" else "incomplets : " + checks.filter { !it.ok }.joinToString { it.label }}" +
                                " ; balises : ${if (problems.isEmpty()) "toutes OK" else problems.joinToString { "${it.name} (${statuses[it.id]?.label})" }}"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Valider ma prise de poste") }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun CheckLine(label: String, ok: Boolean) {
    val c = AppStatusColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (ok) "OK" else "À corriger",
            tint = if (ok) c.safe else c.danger,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
