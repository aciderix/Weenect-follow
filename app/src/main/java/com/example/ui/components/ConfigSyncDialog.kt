package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.FacilityZone
import com.example.data.model.Resident
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.util.BackupData
import com.example.util.ConfigBackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSyncDialog(
    currentZone: FacilityZone,
    residents: List<Resident>,
    onDismiss: () -> Unit,
    onExportShared: (exportedBy: String) -> Unit,
    onImportApplied: (backupData: BackupData, replaceExisting: Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Exporter, 1 = Importer
    var exportedByName by remember { mutableStateOf("Équipe Soignante") }

    // État d'import
    var pastedJsonText by remember { mutableStateOf("") }
    var parsedBackupData by remember { mutableStateOf<BackupData?>(null) }
    var parseErrorMessage by remember { mutableStateOf<String?>(null) }
    var showPasteField by remember { mutableStateOf(false) }

    // Launcher pour ouvrir un fichier .json
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = ConfigBackupManager.readJsonFromUri(context, uri)
            if (!content.isNullOrBlank()) {
                val data = ConfigBackupManager.parseBackupJson(content)
                if (data != null) {
                    parsedBackupData = data
                    parseErrorMessage = null
                    Toast.makeText(context, "Fichier chargé : ${data.facilityZone.name} (${data.residents.size} résidents)", Toast.LENGTH_SHORT).show()
                } else {
                    parseErrorMessage = "Le fichier sélectionné n'est pas un fichier de configuration SécuriRésident valide."
                }
            } else {
                parseErrorMessage = "Impossible de lire le contenu du fichier sélectionné."
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // En-tête
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = SafeNavy.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = SafeNavy)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Sauvegarde & Flotte",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Transfert 1-clic entre appareils soignants",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }

                // Onglets Exporter / Importer
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Exporter", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Importer", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                if (selectedTab == 0) {
                    // ==========================================
                    // ONGLET EXPORTER
                    // ==========================================
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Contenu du fichier d'exportation :",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Apartment, contentDescription = null, modifier = Modifier.size(16.dp), tint = SafeNavy)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Établissement : ${currentZone.name}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp), tint = SafeNavy)
                                Spacer(modifier = Modifier.width(8.dp))
                                val zoneDesc = if (currentZone.zoneType == "POLYGON") {
                                    "Tracé polygone (${currentZone.getPolygonPoints().size} bornes GPS)"
                                } else {
                                    "Cercle de sécurité (${currentZone.radiusMeters.toInt()}m)"
                                }
                                Text(
                                    text = "Zone de surveillance : $zoneDesc",
                                    fontSize = 13.sp
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp), tint = SafeNavy)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Fiches résidents & balises : ${residents.size} résident(s)",
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = exportedByName,
                        onValueChange = { exportedByName = it },
                        label = { Text("Exporté par (Nom ou service soignant)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Bouton principal d'export
                    Button(
                        onClick = {
                            onExportShared(exportedByName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("export_share_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SafeNavy)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Partager la configuration (WhatsApp, Drive, Mail...)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    // Bouton secondaire : Copier le code JSON
                    OutlinedButton(
                        onClick = {
                            val json = ConfigBackupManager.createBackupJson(currentZone, residents, exportedByName)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SecuriResident_Config", json))
                            Toast.makeText(context, "Code de configuration copié dans le presse-papiers", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copier le code JSON (Presse-papiers)")
                    }

                } else {
                    // ==========================================
                    // ONGLET IMPORTER
                    // ==========================================
                    Text(
                        text = "Sélectionnez le fichier de configuration reçu d'un collègue pour paramétrer ce smartphone en 1 clic :",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Bouton sélection fichier
                    Button(
                        onClick = {
                            openDocumentLauncher.launch("*/*")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("import_file_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choisir un fichier de configuration (.json)", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    // Option coller texte manuel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showPasteField = !showPasteField }) {
                            Text(if (showPasteField) "Masquer la zone de texte" else "Ou coller le code texte manuellement")
                        }
                    }

                    if (showPasteField) {
                        OutlinedTextField(
                            value = pastedJsonText,
                            onValueChange = {
                                pastedJsonText = it
                                parseErrorMessage = null
                            },
                            label = { Text("Coller le texte JSON ici") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 150.dp),
                            shape = RoundedCornerShape(12.dp)
                        )

                        FilledTonalButton(
                            onClick = {
                                if (pastedJsonText.isNotBlank()) {
                                    val data = ConfigBackupManager.parseBackupJson(pastedJsonText)
                                    if (data != null) {
                                        parsedBackupData = data
                                        parseErrorMessage = null
                                    } else {
                                        parseErrorMessage = "Texte JSON invalide ou incomplet."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Analyser le code collé")
                        }
                    }

                    // Erreur éventuelle
                    if (parseErrorMessage != null) {
                        Surface(
                            color = AlertRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(parseErrorMessage!!, color = AlertRed, fontSize = 12.sp)
                            }
                        }
                    }

                    // Aperçu des données prêtes à être importées
                    if (parsedBackupData != null) {
                        val backup = parsedBackupData!!
                        val dateFormatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(backup.exportTimestamp))

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SafeGreen.copy(alpha = 0.12f)),
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Sauvegarde valide détectée !",
                                        fontWeight = FontWeight.Bold,
                                        color = SafeGreen,
                                        fontSize = 14.sp
                                    )
                                }

                                Text("• Établissement : ${backup.facilityZone.name}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                val zoneTypeDesc = if (backup.facilityZone.zoneType == "POLYGON") {
                                    "Tracé polygone (${backup.facilityZone.getPolygonPoints().size} bornes)"
                                } else {
                                    "Cercle de sécurité (${backup.facilityZone.radiusMeters.toInt()}m)"
                                }
                                Text("• Zone : $zoneTypeDesc", fontSize = 13.sp)
                                Text("• Résidents inclus : ${backup.residents.size} fiche(s)", fontSize = 13.sp)
                                Text("• Exporté le : $dateFormatted par ${backup.exportedBy}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                Spacer(modifier = Modifier.height(6.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(6.dp))

                                Text("Choisissez le mode d'application :", fontWeight = FontWeight.Bold, fontSize = 12.sp)

                                // Option 1 : Remplacer tout (Nouvel appareil)
                                Button(
                                    onClick = {
                                        onImportApplied(backup, true)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SafeNavy),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Remplacer tout (Nouvel appareil)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                // Option 2 : Fusionner / Ajouter
                                OutlinedButton(
                                    onClick = {
                                        onImportApplied(backup, false)
                                        onDismiss()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.MergeType, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Fusionner / Mettre à jour l'existant", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
