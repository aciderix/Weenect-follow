package fr.alerteresidents.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import fr.alerteresidents.util.AlarmSound
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import fr.alerteresidents.ui.components.CloudSyncSection
import fr.alerteresidents.ui.components.ConfigSyncDialog
import fr.alerteresidents.ui.components.SetPinDialog
import fr.alerteresidents.ui.components.VisualZonePickerDialog
import fr.alerteresidents.ui.components.WeenectAccountsDialog
import fr.alerteresidents.ui.components.ZoneEditorDialog
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.SafeNavy
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ResidentViewModel, onOpenStatus: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val facilityZone by viewModel.facilityZone.collectAsState()
    val residents by viewModel.residents.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val staffName by viewModel.prefs.staffName.collectAsState()
    val hasPin by viewModel.prefs.hasPin.collectAsState()
    val alarmSound by viewModel.prefs.alarmSound.collectAsState()

    var showZoneEditor by remember { mutableStateOf(false) }
    var showVisualMapPicker by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var nameDraft by remember(staffName) { mutableStateOf(staffName) }
    var staleMinutes by remember { mutableFloatStateOf(viewModel.prefs.staleMinutes.toFloat()) }
    var reminderMinutes by remember { mutableFloatStateOf(viewModel.prefs.reminderMinutes.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard(Icons.Default.Badge, "Ce téléphone", "Nom affiché sur les prises en charge et acquittements") {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Nom du soignant / du poste") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameDraft != staffName) {
                    Button(onClick = { viewModel.setStaffName(nameDraft) }) { Text("Enregistrer le nom") }
                }
            }

            SettingsCard(Icons.Default.FactCheck, "État de la surveillance", "Diagnostic, réglages du téléphone, prise de poste") {
                Button(onClick = onOpenStatus, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir l'état de la surveillance") }
            }

            SettingsCard(Icons.Default.LocationOn, "Établissement et zones", facilityZone.name) {
                Text(
                    buildString {
                        append(if (facilityZone.isPolygon) "Zone tracée (${facilityZone.getPolygonPoints().size} points)" else "Cercle de ${facilityZone.radiusMeters.toInt()} m")
                        val extras = facilityZone.getExtraZones()
                        if (extras.isNotEmpty()) append(" • ${extras.size} zone(s) annexe(s)")
                        if (facilityZone.nightModeEnabled) append(" • nuit ${facilityZone.nightStartHour} h – ${facilityZone.nightEndHour} h")
                        if (!facilityZone.isZoneActive) append(" • ZONE DÉSACTIVÉE")
                    },
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showVisualMapPicker = true }, modifier = Modifier.weight(1f)) { Text("Tracer sur la carte") }
                    OutlinedButton(onClick = { showZoneEditor = true }, modifier = Modifier.weight(1f)) { Text("Réglages, annexes, nuit") }
                }
            }

            SettingsCard(Icons.Default.AccountCircle, "Comptes Weenect", "${accounts.size} compte(s) • mots de passe chiffrés") {
                Button(onClick = { showAccounts = true }, modifier = Modifier.fillMaxWidth()) { Text("Gérer les comptes Weenect") }
            }

            SettingsCard(Icons.Default.NotificationsActive, "Alertes", "Délais d'avertissement et de rappel") {
                Text("Signal considéré comme ancien après ${staleMinutes.toInt()} min", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = staleMinutes, onValueChange = { staleMinutes = it },
                    onValueChangeFinished = { viewModel.prefs.staleMinutes = staleMinutes.toInt() },
                    valueRange = 5f..60f, steps = 10
                )
                Text("Rappel sonore si une sortie n'est pas prise en charge après ${reminderMinutes.toInt()} min", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = reminderMinutes, onValueChange = { reminderMinutes = it },
                    onValueChangeFinished = { viewModel.prefs.reminderMinutes = reminderMinutes.toInt() },
                    valueRange = 1f..30f, steps = 28
                )
            }

            SettingsCard(Icons.Default.VolumeUp, "Son de l'alarme", "Volume forcé à 100 %, canal Alarme, vibration") {
                AlarmSound.entries.forEach { sound ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = alarmSound == sound, role = Role.RadioButton) { viewModel.prefs.setAlarmSound(sound) }
                            .testTag("alarm_sound_${sound.name}")
                    ) {
                        RadioButton(selected = alarmSound == sound, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(sound.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(sound.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Choisissez un son puis « Tester maintenant ». Le test se comporte comme une vraie alerte (son, vibration, notification plein écran) mais est marqué « exercice ». " +
                        "Pour un exercice sur un résident, utilisez le menu ⋮ de sa fiche.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.triggerManualLoudAlarmTest() },
                        colors = ButtonDefaults.buttonColors(containerColor = AppStatusColors.dangerSolid, contentColor = Color.White),
                        modifier = Modifier.weight(1f).testTag("btn_test_alarm")
                    ) { Text("Tester maintenant") }
                    OutlinedButton(onClick = { viewModel.triggerLockscreenDelayedAlarmTest(6) }, modifier = Modifier.weight(1f)) {
                        Text("Écran verrouillé (6 s)")
                    }
                }
            }

            SettingsCard(Icons.Default.Lock, "Code PIN des paramètres", if (hasPin) "Activé" else "Désactivé : tout le monde peut modifier les réglages") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSetPin = true }, modifier = Modifier.weight(1f)) { Text(if (hasPin) "Changer le code" else "Définir un code") }
                    if (hasPin) OutlinedButton(onClick = { viewModel.setPin(null) }, modifier = Modifier.weight(1f)) { Text("Supprimer le code") }
                }
            }

            SettingsCard(Icons.Default.Share, "Sauvegarde / nouveau téléphone", "Zone, résidents, comptes (mots de passe chiffrés par un code)") {
                Button(
                    onClick = { showBackupDialog = true },
                    modifier = Modifier.fillMaxWidth().testTag("open_backup_dialog_button")
                ) { Text("Exporter ou importer la configuration") }
            }

            SettingsCard(Icons.Default.Cloud, "Partage entre appareils", "Alertes et prises en charge communes (Supabase)") {
                CloudSyncSection(viewModel)
            }

            SettingsCard(null, "Guide rapide", null) {
                GuideLine("1. Prise de poste", "Ouvrez « État de la surveillance », testez la sonnerie et validez : tout est tracé dans le journal.")
                GuideLine("2. En cas de sortie", "Le téléphone sonne. Appuyez sur « Je m'en occupe » pour que l'équipe sache qui intervient, puis « Guider ».")
                GuideLine("3. Résident retrouvé", "Appuyez sur « Retrouvé » : l'alerte est levée, la balise confirmera le retour dans la zone.")
                GuideLine("4. Sortie prévue", "Menu ⋮ › « Sortie accompagnée » (ou appui long pour sélectionner plusieurs résidents).")
            }
        }
    }

    if (showZoneEditor) {
        ZoneEditorDialog(
            currentZone = facilityZone,
            onDismiss = { showZoneEditor = false },
            onSave = { viewModel.updateFacilityZone(it); showZoneEditor = false },
            onOpenVisualMapPicker = { showZoneEditor = false; showVisualMapPicker = true }
        )
    }
    if (showVisualMapPicker) {
        VisualZonePickerDialog(
            currentZone = facilityZone,
            onDismiss = { showVisualMapPicker = false },
            onZoneSaved = { viewModel.updateFacilityZone(it); showVisualMapPicker = false }
        )
    }
    if (showAccounts) WeenectAccountsDialog(viewModel = viewModel, onDismiss = { showAccounts = false })
    if (showSetPin) SetPinDialog(onDismiss = { showSetPin = false }, onSet = { viewModel.setPin(it); showSetPin = false })
    if (showBackupDialog) {
        ConfigSyncDialog(
            currentZone = facilityZone,
            residents = residents,
            hasAccounts = accounts.isNotEmpty(),
            defaultExportedBy = viewModel.staffName,
            onDismiss = { showBackupDialog = false },
            onExportShared = { exportedBy, passphrase -> viewModel.exportConfiguration(context, exportedBy, passphrase) },
            onImportApplied = { backup, replace, passphrase ->
                viewModel.importConfiguration(backup, replace, passphrase)
                showBackupDialog = false
            }
        )
    }
}

@Composable
private fun SettingsCard(icon: ImageVector?, title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                }
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun GuideLine(title: String, text: String) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text, fontSize = 14.sp)
    }
}
