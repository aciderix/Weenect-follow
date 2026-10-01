package com.example.ui.components

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FacilityZone
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafeNavy
import com.example.util.LocationHelper
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ZoneEditorDialog(
    currentZone: FacilityZone,
    onDismiss: () -> Unit,
    onSave: (FacilityZone) -> Unit,
    onOpenVisualMapPicker: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(currentZone.name) }
    var address by remember { mutableStateOf(currentZone.address) }
    var latText by remember { mutableStateOf(String.format(Locale.US, "%.6f", currentZone.centerLatitude)) }
    var lonText by remember { mutableStateOf(String.format(Locale.US, "%.6f", currentZone.centerLongitude)) }
    var radiusMeters by remember { mutableFloatStateOf(currentZone.radiusMeters.toFloat()) }
    var soundAlerts by remember { mutableStateOf(currentZone.soundAlertsEnabled) }
    var vibrateAlerts by remember { mutableStateOf(currentZone.vibrateAlertsEnabled) }
    var refreshInterval by remember { mutableIntStateOf(currentZone.refreshIntervalSeconds) }
    var isFetchingLocation by remember { mutableStateOf(false) }

    fun fetchGpsLocation() {
        isFetchingLocation = true
        scope.launch {
            try {
                val loc = LocationHelper.getCurrentLocation(context)
                isFetchingLocation = false
                if (loc != null) {
                    latText = String.format(Locale.US, "%.6f", loc.latitude)
                    lonText = String.format(Locale.US, "%.6f", loc.longitude)
                    Toast.makeText(
                        context,
                        "Position GPS fixée avec succès : ${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Impossible de récupérer le GPS. Activez la localisation de l'appareil.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                isFetchingLocation = false
                Toast.makeText(context, "Erreur lors de la capture GPS: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchGpsLocation()
        } else {
            Toast.makeText(
                context,
                "Permission de localisation requise pour fixer la position par GPS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Apartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zone de sécurité établissement", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Définit le périmètre autour de l'établissement. Toute balise sortant de ce cercle déclenche une alerte immédiate sur ce téléphone.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom de l'établissement") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_facility_name")
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Adresse") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_facility_address")
                )

                // Option visuelle carte
                if (onOpenVisualMapPicker != null) {
                    Button(
                        onClick = onOpenVisualMapPicker,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SafeNavy,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🗺️ Positionner & tracer sur la carte", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Slider rayon
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Rayon de sécurité :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                text = "${radiusMeters.toInt()} mètres",
                                fontWeight = FontWeight.Bold,
                                color = SafeGreen,
                                fontSize = 14.sp
                            )
                        }
                        Slider(
                            value = radiusMeters,
                            onValueChange = { radiusMeters = it },
                            valueRange = 30f..800f,
                            steps = 15,
                            modifier = Modifier.testTag("slider_safety_radius")
                        )
                        Text(
                            text = "Ex: 100m pour cour/bâtiment, 250m pour parc/jardins",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Coordonnées du centre
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("input_zone_lat")
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("input_zone_lon")
                    )
                }

                // Bouton Prendre ma position actuelle (réel GPS téléphone)
                OutlinedButton(
                    onClick = {
                        if (LocationHelper.hasLocationPermission(context)) {
                            fetchGpsLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isFetchingLocation) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Recherche du signal GPS...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Prendre ma position GPS actuelle", fontSize = 12.sp)
                    }
                }

                // Toggles alertes sonores et vibrations
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Alarme sonore hors zone", fontSize = 13.sp)
                    }
                    Switch(checked = soundAlerts, onCheckedChange = { soundAlerts = it }, modifier = Modifier.testTag("switch_sound_alerts"))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Vibration alarme", fontSize = 13.sp)
                    }
                    Switch(checked = vibrateAlerts, onCheckedChange = { vibrateAlerts = it }, modifier = Modifier.testTag("switch_vibrate_alerts"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedLat = latText.trim().replace(',', '.').toDoubleOrNull() ?: currentZone.centerLatitude
                    val parsedLon = lonText.trim().replace(',', '.').toDoubleOrNull() ?: currentZone.centerLongitude
                    onSave(
                        currentZone.copy(
                            name = name.trim().ifEmpty { "Établissement" },
                            address = address.trim().ifEmpty { currentZone.address },
                            centerLatitude = parsedLat,
                            centerLongitude = parsedLon,
                            radiusMeters = radiusMeters.toDouble(),
                            soundAlertsEnabled = soundAlerts,
                            vibrateAlertsEnabled = vibrateAlerts,
                            refreshIntervalSeconds = refreshInterval
                        )
                    )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("btn_save_zone")
            ) {
                Text("Appliquer la zone", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text("Annuler", fontSize = 14.sp)
            }
        }
    )
}
