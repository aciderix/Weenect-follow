package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Resident
import com.example.data.model.WeenectTrackerDto
import com.example.ui.theme.AlertRed
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import kotlinx.coroutines.launch

@Composable
fun AddResidentDialog(
    initialResident: Resident? = null,
    onDismiss: () -> Unit,
    onSave: (Resident) -> Unit,
    onTestWeenect: suspend (username: String, pass: String) -> Result<List<WeenectTrackerDto>>
) {
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialResident?.name ?: "") }
    var roomNumber by remember { mutableStateOf(initialResident?.roomNumber ?: "") }
    var weenectEmail by remember { mutableStateOf(initialResident?.weenectUsername ?: "") }
    var weenectPassword by remember { mutableStateOf(initialResident?.weenectPassword ?: "") }
    var trackerIdText by remember { mutableStateOf(initialResident?.trackerId?.toString() ?: "") }
    var trackerName by remember { mutableStateOf(initialResident?.trackerName ?: "") }
    var emergencyContact by remember { mutableStateOf(initialResident?.emergencyContact ?: "") }
    var notes by remember { mutableStateOf(initialResident?.notes ?: "") }
    var selectedColor by remember { mutableStateOf(initialResident?.avatarColorHex ?: "#1E88E5") }

    var isTestingWeenect by remember { mutableStateOf(false) }
    var weenectTestResult by remember { mutableStateOf<String?>(null) }
    var weenectTestSuccess by remember { mutableStateOf<Boolean?>(null) }
    var detectedTrackers by remember { mutableStateOf<List<WeenectTrackerDto>>(emptyList()) }

    val colorOptions = listOf("#1E88E5", "#43A047", "#8E24AA", "#E53935", "#FB8C00", "#00ACC1")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialResident == null) "Ajouter un résident" else "Modifier le résident",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Informations simples pour le personnel de soins et de garde.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Nom du résident
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom et Prénom du résident *") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_resident_name")
                )

                // Chambre / Pavillon
                OutlinedTextField(
                    value = roomNumber,
                    onValueChange = { roomNumber = it },
                    label = { Text("Chambre ou Service") },
                    placeholder = { Text("Ex: Chambre 14, Pavillon B") },
                    leadingIcon = { Icon(Icons.Default.MeetingRoom, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_resident_room")
                )

                // Choix de couleur d'avatar
                Text(
                    text = "Couleur du repère sur la carte :",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorOptions.forEach { hex ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == hex) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Sélectionné",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Section Balise Weenect
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Balise GPS Weenect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Entrez le compte Weenect du résident ou cliquez sur Détecter pour lier son boîtier automatiquement.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = weenectEmail,
                            onValueChange = { weenectEmail = it },
                            label = { Text("Email Weenect du résident") },
                            placeholder = { Text("ex: famille.resident@mail.com") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_weenect_email")
                        )

                        OutlinedTextField(
                            value = weenectPassword,
                            onValueChange = { weenectPassword = it },
                            label = { Text("Mot de passe Weenect") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_weenect_password")
                        )

                        // Bouton Détecter
                        Button(
                            onClick = {
                                if (weenectEmail.isNotEmpty() && weenectPassword.isNotEmpty()) {
                                    isTestingWeenect = true
                                    weenectTestResult = null
                                    coroutineScope.launch {
                                        val res = onTestWeenect(weenectEmail, weenectPassword)
                                        isTestingWeenect = false
                                        if (res.isSuccess) {
                                            val trackers = res.getOrNull() ?: emptyList()
                                            detectedTrackers = trackers
                                            if (trackers.isNotEmpty()) {
                                                val first = trackers.first()
                                                trackerIdText = first.id.toString()
                                                trackerName = first.name ?: "Balise Weenect"
                                                weenectTestSuccess = true
                                                weenectTestResult = "Balise trouvée : ${first.name ?: first.id}"
                                            } else {
                                                weenectTestSuccess = true
                                                weenectTestResult = "Compte validé mais aucun boîtier actif trouvé"
                                            }
                                        } else {
                                            weenectTestSuccess = false
                                            weenectTestResult = "Erreur de connexion : ${res.exceptionOrNull()?.message}"
                                        }
                                    }
                                } else {
                                    weenectTestSuccess = false
                                    weenectTestResult = "Veuillez renseigner email et mot de passe"
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_detect_weenect"),
                            enabled = !isTestingWeenect,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isTestingWeenect) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connexion Weenect...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Détecter le boîtier automatiquement", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (weenectTestResult != null) {
                            Text(
                                text = weenectTestResult!!,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (weenectTestSuccess == true) SafeGreen else AlertRed
                            )
                        }

                        // ID Balise manuel (si pas de login ou pour confirmer)
                        OutlinedTextField(
                            value = trackerIdText,
                            onValueChange = { trackerIdText = it },
                            label = { Text("Numéro / ID du boîtier GPS") },
                            placeholder = { Text("Ex: 104281") },
                            leadingIcon = { Icon(Icons.Default.GpsFixed, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_tracker_id")
                        )
                    }
                }

                // Contact d'urgence / Médecin / Famille
                OutlinedTextField(
                    value = emergencyContact,
                    onValueChange = { emergencyContact = it },
                    label = { Text("Contact d'urgence / Famille") },
                    placeholder = { Text("Ex: Tuteur 06 12 34 56 78") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes soignantes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Consignes particulières / Notes") },
                    placeholder = { Text("Ex: Porteur de fauteuil, déambulation habituelle parc") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        val residentToSave = (initialResident ?: Resident(name = name)).copy(
                            name = name.trim(),
                            roomNumber = roomNumber.trim(),
                            avatarColorHex = selectedColor,
                            weenectUsername = weenectEmail.trim(),
                            weenectPassword = weenectPassword.trim(),
                            trackerId = trackerIdText.toLongOrNull() ?: initialResident?.trackerId ?: 100000L,
                            trackerName = trackerName.ifEmpty { "Balise Weenect $name" },
                            emergencyContact = emergencyContact.trim(),
                            notes = notes.trim()
                        )
                        onSave(residentToSave)
                    }
                },
                enabled = name.trim().isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("btn_save_resident")
            ) {
                Text("Enregistrer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("btn_cancel_resident")
            ) {
                Text("Annuler", fontSize = 14.sp)
            }
        }
    )
}
