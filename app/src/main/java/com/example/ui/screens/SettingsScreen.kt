package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ResidentMonitoringService
import com.example.data.remote.SupabaseSyncService
import com.example.ui.components.ConfigSyncDialog
import com.example.ui.components.VisualZonePickerDialog
import com.example.ui.components.ZoneEditorDialog
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.ui.viewmodel.ResidentViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ResidentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val facilityZone by viewModel.facilityZone.collectAsState()
    val residents by viewModel.residents.collectAsState()
    val isAlarmRinging by viewModel.isAlarmRinging.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showZoneEditor by remember { mutableStateOf(false) }
    var showVisualMapPicker by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var isTestingSupabase by remember { mutableStateOf(false) }
    var supabaseStatusMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres & Synchronisation", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SafeNavy)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Zone de sécurité
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SafeNavy.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Apartment, contentDescription = null, tint = SafeNavy)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Zone de l'Établissement", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(facilityZone.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Rayon de sécurité défini : ${facilityZone.radiusMeters.toInt()} mètres",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = SafeGreen
                    )
                    Text(
                        text = "Centre GPS : ${facilityZone.centerLatitude}, ${facilityZone.centerLongitude}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showVisualMapPicker = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SafeNavy,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.3f)
                                .testTag("settings_visual_map_button")
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🎯 Tracer la zone", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showZoneEditor = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_edit_zone_button")
                        ) {
                            Text("Modifier détails", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Test de l'alarme
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AlertRed)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Test des alertes sonores & vibration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Cette fonction teste la sonnerie d'urgence maximale telle qu'elle se déclenche lors d'une sortie de zone : volume forcé à 100%, sonnerie continue d'alarme et vibration répétée pour réveiller ou alerter immédiatement le soignant.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = AlertRed.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("🔊 Canal d'alarme : STREAM_ALARM (Bouton silencieux ignoré)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AlertRed)
                            Text("📳 Vibration : Mode continu haute intensité", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AlertRed)
                            Text("📱 Notification : Affichage plein écran sur écran verrouillé", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AlertRed)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.triggerManualLoudAlarmTest()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .testTag("btn_test_loud_alarm")
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tester sonnerie (100%)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.silenceAlarm() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(0.7f)
                                .testTag("btn_silence_alarm_test")
                        ) {
                            Text("Couper", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Bouton spécial de test écran verrouillé avec compte à rebours
                    FilledTonalButton(
                        onClick = {
                            viewModel.triggerLockscreenDelayedAlarmTest(6)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_test_delayed_lockscreen_alarm"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "⏱️ Tester sur écran verrouillé (6 sec)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Surveillance continue en arrière-plan (24h/24 même application fermée)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val context = LocalContext.current
                val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
                var isIgnoringBattery by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        } else true
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = SafeGreen)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Surveillance continue 24h/24", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Un service d'arrière-plan permanent surveille les balises 24h/24 même si vous quittez ou fermez complètement l'application. Pour éviter qu'Android n'endorme les vérifications en veille prolongée, autorisez l'exécution en arrière-plan sans restriction de batterie.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = if (isIgnoringBattery) SafeGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isIgnoringBattery) SafeGreen else AlertRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isIgnoringBattery)
                                    "Arrière-plan sans restriction : alertes garanties même téléphone verrouillé ou app fermée."
                                else
                                    "Optimisation batterie active : risque de mise en veille prolongée par Android.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isIgnoringBattery) SafeGreen else AlertRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isIgnoringBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SafeNavy),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Autoriser 24h/24", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                ResidentMonitoringService.start(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Relancer service", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Sauvegarde & Exportation Flotte soignante (1-Clic)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SafeNavy.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = SafeNavy)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Sauvegarde & Flotte d'appareils", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Cloner ou mettre à jour un smartphone en 1 clic", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Exporte l'ensemble des fiches résidents, balises Weenect et tracé du périmètre GPS pour configurer immédiatement un nouveau téléphone soignant sans ressaisir.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showBackupDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_backup_dialog_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SafeNavy)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exporter ou Importer la configuration", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            // Cloud Supabase Sync
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Partage Multi-Téléphones (Cloud)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Synchronisation Infirmières & Chefs de service", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Connecté au projet Supabase de l'établissement :",
                        fontSize = 12.sp
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "https://iphngnvzdqmuibscmtjk.supabase.co",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    FilledTonalButton(
                        onClick = {
                            isTestingSupabase = true
                            supabaseStatusMessage = null
                            coroutineScope.launch {
                                val s = SupabaseSyncService()
                                val r = s.testConnection()
                                isTestingSupabase = false
                                supabaseStatusMessage = if (r.isSuccess) {
                                    "Synchronisation active avec le serveur établissement"
                                } else {
                                    "Erreur : ${r.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingSupabase) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tester la liaison serveur Supabase")
                        }
                    }

                    if (supabaseStatusMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = supabaseStatusMessage!!,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SafeGreen
                        )
                    }
                }
            }

            // Guide simplifié d'utilisation
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Guide rapide pour l'équipe soignante", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("1. Surveillance automatique :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("L'application vérifie en permanence que les résidents restent dans le cercle vert de l'établissement.", fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("2. En cas de sortie d'enceinte :", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AlertRed)
                    Text("Le téléphone sonne immédiatement, affiche la distance et permet en 1 clic de lancer le GPS vers le résident.", fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("3. Retrouver un résident :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Le bouton 'Faire sonner' permet de localiser rapidement le boîtier Weenect au bruit dans le parc ou les espaces verts.", fontSize = 12.sp)
                }
            }
        }
    }

    if (showZoneEditor) {
        ZoneEditorDialog(
            currentZone = facilityZone,
            onDismiss = { showZoneEditor = false },
            onSave = {
                viewModel.updateFacilityZone(it)
                showZoneEditor = false
            },
            onOpenVisualMapPicker = {
                showZoneEditor = false
                showVisualMapPicker = true
            }
        )
    }

    if (showVisualMapPicker) {
        VisualZonePickerDialog(
            currentZone = facilityZone,
            onDismiss = { showVisualMapPicker = false },
            onZoneSaved = { updatedZone ->
                viewModel.updateFacilityZone(updatedZone)
                showVisualMapPicker = false
            }
        )
    }

    if (showBackupDialog) {
        ConfigSyncDialog(
            currentZone = facilityZone,
            residents = residents,
            onDismiss = { showBackupDialog = false },
            onExportShared = { exportedBy ->
                viewModel.exportConfiguration(context, exportedBy)
            },
            onImportApplied = { backupData, replaceExisting ->
                viewModel.importConfiguration(backupData, replaceExisting)
            }
        )
    }
}
