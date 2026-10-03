package fr.alerteresidents.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.util.BackupData
import fr.alerteresidents.util.ConfigBackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export / import de la configuration. Les mots de passe Weenect ne quittent le téléphone
 * que chiffrés par un code choisi au moment de l'export.
 */
@Composable
fun ConfigSyncDialog(
    currentZone: FacilityZone,
    residents: List<Resident>,
    hasAccounts: Boolean,
    defaultExportedBy: String,
    onDismiss: () -> Unit,
    onExportShared: (exportedBy: String, passphrase: String?) -> Unit,
    onImportApplied: (backup: BackupData, replaceExisting: Boolean, passphrase: String?) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var exportedBy by remember { mutableStateOf(defaultExportedBy) }
    var includePasswords by remember { mutableStateOf(hasAccounts) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }

    var parsed by remember { mutableStateOf<BackupData?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var importPassphrase by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val content = ConfigBackupManager.readJsonFromUri(context, uri)
            parsed = content?.let { ConfigBackupManager.parseBackupJson(it) }
            parseError = if (parsed == null) "Fichier illisible ou invalide" else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sauvegarde de la configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Exporter") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Importer") })
                }
                if (tab == 0) {
                    Text(
                        "${currentZone.name} • ${residents.size} résident(s). Les photos et l'historique ne sont pas exportés.",
                        fontSize = 14.sp
                    )
                    OutlinedTextField(exportedBy, { exportedBy = it }, label = { Text("Exporté par") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (hasAccounts) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Inclure les mots de passe Weenect", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("Chiffrés par un code à communiquer séparément (oralement, pas dans le même message).",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = includePasswords, onCheckedChange = { includePasswords = it })
                        }
                        if (includePasswords) {
                            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Code de chiffrement (6 caractères min.)") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(passphraseConfirm, { passphraseConfirm = it }, label = { Text("Confirmer le code") }, singleLine = true,
                                isError = passphraseConfirm.isNotEmpty() && passphraseConfirm != passphrase,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text("Le fichier contient des données de santé : ne le partagez qu'avec l'équipe et supprimez-le après import.",
                        fontSize = 13.sp, color = AppStatusColors.warning, fontWeight = FontWeight.SemiBold)
                    val exportReady = !includePasswords || !hasAccounts || (passphrase.length >= 6 && passphrase == passphraseConfirm)
                    Button(
                        enabled = exportReady,
                        onClick = { onExportShared(exportedBy.ifBlank { defaultExportedBy }, if (includePasswords && hasAccounts) passphrase else null) },
                        modifier = Modifier.fillMaxWidth().testTag("btn_export_config")
                    ) { Text("Générer et partager le fichier") }
                } else {
                    val data = parsed
                    if (data == null) {
                        OutlinedButton(onClick = { openDocument.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Choisir un fichier .json")
                        }
                        TextButton(onClick = { pasteMode = !pasteMode }) { Text(if (pasteMode) "Masquer le collage" else "Ou coller le contenu JSON") }
                        if (pasteMode) {
                            OutlinedTextField(pasted, { pasted = it }, label = { Text("Contenu JSON") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                parsed = ConfigBackupManager.parseBackupJson(pasted)
                                parseError = if (parsed == null) "Contenu invalide" else null
                            }) { Text("Analyser") }
                        }
                        parseError?.let { Text(it, color = AppStatusColors.danger) }
                    } else {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(data.facilityZone.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${data.residents.size} résident(s) • ${data.accounts.size} compte(s) Weenect", fontSize = 14.sp)
                                Text(
                                    "Exporté par ${data.exportedBy} le ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(data.exportTimestamp))}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (data.hasLegacyPlaintextCredentials) {
                                    Text("Ancien format : les mots de passe seront chiffrés à l'import.", fontSize = 13.sp, color = AppStatusColors.warning)
                                }
                            }
                        }
                        if (data.hasEncryptedPasswords) {
                            OutlinedTextField(importPassphrase, { importPassphrase = it }, label = { Text("Code de chiffrement des mots de passe") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Text("Sans le code, les comptes sont importés et les mots de passe devront être ressaisis.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { onImportApplied(data, false, importPassphrase.ifBlank { null }) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Fusionner avec les résidents existants") }
                        OutlinedButton(
                            onClick = { onImportApplied(data, true, importPassphrase.ifBlank { null }) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppStatusColors.danger),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Tout remplacer (supprime les résidents actuels)") }
                        TextButton(onClick = { parsed = null }) { Text("Choisir un autre fichier") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
