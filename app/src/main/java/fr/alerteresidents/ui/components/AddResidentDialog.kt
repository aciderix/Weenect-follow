package fr.alerteresidents.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.ResidentProfile
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.data.model.WeenectTrackerDto
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.viewmodel.ResidentViewModel

private val avatarColors = listOf("#1E88E5", "#43A047", "#E53935", "#8E24AA", "#FB8C00", "#00897B", "#6D4C41", "#3949AB")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddResidentDialog(
    initialResident: Resident?,
    accounts: List<WeenectAccount>,
    viewModel: ResidentViewModel,
    onDismiss: () -> Unit,
    onSave: (ResidentProfile, previousPhoto: String?) -> Unit,
    onManageAccounts: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialResident?.name ?: "") }
    var roomNumber by remember { mutableStateOf(initialResident?.roomNumber ?: "") }
    var unit by remember { mutableStateOf(initialResident?.unit ?: "") }
    var riskLevel by remember { mutableIntStateOf(initialResident?.riskLevel ?: 0) }
    var accountId by remember { mutableStateOf(initialResident?.accountId ?: accounts.singleOrNull()?.id) }
    var trackerIdText by remember { mutableStateOf(initialResident?.trackerId?.toString() ?: "") }
    var trackerName by remember { mutableStateOf(initialResident?.trackerName ?: "") }
    var emergencyContact by remember { mutableStateOf(initialResident?.emergencyContact ?: "") }
    var notes by remember { mutableStateOf(initialResident?.notes ?: "") }
    var color by remember { mutableStateOf(initialResident?.avatarColorHex ?: avatarColors.first()) }
    var photo by remember { mutableStateOf(initialResident?.photoUri) }
    var trackingActive by remember { mutableStateOf(initialResident?.isTrackingActive ?: true) }

    var trackers by remember { mutableStateOf<List<WeenectTrackerDto>>(emptyList()) }
    var trackersLoading by remember { mutableStateOf(false) }
    var trackersError by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<Resident>>(emptyList()) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) viewModel.importPhoto(context, uri) { path -> if (path != null) photo = path }
    }

    // Liste des balises du compte choisi
    LaunchedEffect(accountId) {
        trackers = emptyList()
        trackersError = null
        val id = accountId ?: return@LaunchedEffect
        trackersLoading = true
        viewModel.trackersForAccount(id).fold(
            { trackers = it },
            { trackersError = it.message }
        )
        trackersLoading = false
    }
    // Avertissement : balise déjà associée à un autre résident
    LaunchedEffect(trackerIdText) {
        duplicates = trackerIdText.toLongOrNull()?.let { viewModel.residentsUsingTracker(it, initialResident?.id ?: 0L) } ?: emptyList()
    }

    val trackerId = trackerIdText.toLongOrNull()
    val canSave = name.isNotBlank() && (!trackingActive || (trackerId != null && accountId != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialResident == null) "Nouveau résident" else "Modifier la fiche", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Photo + couleur
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        ResidentAvatar(Resident(name = name.ifBlank { "?" }, avatarColorHex = color, photoUri = photo), 64.dp)
                        if (photo == null) Icon(Icons.Default.AddAPhoto, contentDescription = "Ajouter une photo", tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.BottomEnd).size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Photo (aide les remplaçants à reconnaître le résident)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Text(if (photo == null) "Choisir" else "Changer")
                            }
                            if (photo != null) TextButton(onClick = { photo = null }) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)); Text("Retirer")
                            }
                        }
                    }
                }
                if (photo == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        avatarColors.forEach { hex ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                Modifier.size(30.dp).background(c, CircleShape)
                                    .border(if (hex == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { color = hex }
                            )
                        }
                    }
                }

                OutlinedTextField(name, { name = it }, label = { Text("Nom et prénom *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_resident_name"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(roomNumber, { roomNumber = it }, label = { Text("Chambre") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unité / étage") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Niveau de vigilance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..2).forEach { level ->
                        FilterChip(selected = riskLevel == level, onClick = { riskLevel = level }, label = { Text(riskLabel(level), fontSize = 14.sp) })
                    }
                }

                // Balise
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Suivi par balise Weenect", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(checked = trackingActive, onCheckedChange = { trackingActive = it })
                        }
                        if (accounts.isEmpty()) {
                            Text("Aucun compte Weenect enregistré.", fontSize = 14.sp, color = AppStatusColors.warning)
                            OutlinedButton(onClick = onManageAccounts) { Text("Ajouter un compte Weenect") }
                        } else {
                            Text("Compte Weenect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                accounts.forEach { a ->
                                    FilterChip(
                                        selected = accountId == a.id,
                                        onClick = { accountId = a.id },
                                        label = { Text(a.label, fontSize = 14.sp) },
                                        modifier = Modifier.testTag("chip_account_${a.id}")
                                    )
                                }
                                TextButton(onClick = onManageAccounts) { Text("Gérer…") }
                            }
                            when {
                                trackersLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp)); Text("Chargement des balises…", fontSize = 14.sp)
                                }
                                trackersError != null -> Text("Balises indisponibles : $trackersError", fontSize = 14.sp, color = AppStatusColors.danger)
                                trackers.isNotEmpty() -> {
                                    Text("Balises du compte (touchez pour associer) :", fontSize = 14.sp)
                                    trackers.forEach { t ->
                                        val chosen = trackerIdText == t.id.toString()
                                        Surface(
                                            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                trackerIdText = t.id.toString(); trackerName = t.name ?: ""
                                            }
                                        ) {
                                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (chosen) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Text("${t.name ?: "Balise"}  •  n° ${t.id}", fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = trackerIdText,
                            onValueChange = { trackerIdText = it.filter(Char::isDigit) },
                            label = { Text("N° de balise") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (duplicates.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AppStatusColors.warning, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Cette balise est déjà associée à : ${duplicates.joinToString { it.name }}",
                                    fontSize = 14.sp, color = AppStatusColors.warning, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (trackingActive && (trackerId == null || accountId == null)) {
                            Text("Choisissez un compte et une balise, ou désactivez le suivi.", fontSize = 13.sp, color = AppStatusColors.danger)
                        }
                    }
                }

                OutlinedTextField(emergencyContact, { emergencyContact = it }, label = { Text("Contact d'urgence") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (tenue, habitudes, lieux fréquents…)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        ResidentProfile(
                            id = initialResident?.id ?: 0L,
                            name = name.trim(),
                            roomNumber = roomNumber.trim(),
                            photoUri = photo,
                            avatarColorHex = color,
                            accountId = accountId,
                            trackerId = trackerId,
                            trackerName = trackerName.ifBlank { null },
                            emergencyContact = emergencyContact.trim(),
                            notes = notes.trim(),
                            isTrackingActive = trackingActive,
                            unit = unit.trim(),
                            riskLevel = riskLevel
                        ),
                        initialResident?.photoUri
                    )
                },
                modifier = Modifier.testTag("btn_save_resident")
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
