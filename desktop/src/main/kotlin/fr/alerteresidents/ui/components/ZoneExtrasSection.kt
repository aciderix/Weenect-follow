package fr.alerteresidents.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.ExtraZone
import fr.alerteresidents.ui.theme.AppStatusColors

/** Réglages avancés de la zone : activation, fréquence, zones annexes, mode nuit. */
@Composable
fun ZoneExtrasSection(
    zoneActive: Boolean, onZoneActive: (Boolean) -> Unit,
    refreshInterval: Int, onRefreshInterval: (Int) -> Unit,
    extraZones: List<ExtraZone>, onExtraZones: (List<ExtraZone>) -> Unit,
    defaultCenter: Pair<Double, Double>,
    nightEnabled: Boolean, onNightEnabled: (Boolean) -> Unit,
    nightStart: Int, onNightStart: (Int) -> Unit,
    nightEnd: Int, onNightEnd: (Int) -> Unit,
    nightInterval: Int, onNightInterval: (Int) -> Unit
) {
    val panel = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Surveillance de zone active", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (!zoneActive) Text("Désactivée : aucune alarme de sortie ne sera déclenchée !", fontSize = 13.sp, color = AppStatusColors.danger)
            }
            Switch(checked = zoneActive, onCheckedChange = onZoneActive)
        }

        Text("Vérification des balises toutes les $refreshInterval s", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Slider(value = refreshInterval.toFloat(), onValueChange = { onRefreshInterval(it.toInt()) }, valueRange = 10f..120f, steps = 10)

        Card(colors = panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zones annexes autorisées", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Jardin, parking, bâtiment voisin… Un résident dans une zone annexe n'est pas en alerte.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extraZones.forEachIndexed { index, z -> key(index, extraZones.size) {
                    fun update(n: ExtraZone) = onExtraZones(extraZones.toMutableList().also { it[index] = n })
                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(z.name, { update(z.copy(name = it)) }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onExtraZones(extraZones.filterIndexed { i, _ -> i != index }) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer la zone ${z.name}", tint = AppStatusColors.danger)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                DecimalField("Latitude", z.latitude, Modifier.weight(1f)) { update(z.copy(latitude = it)) }
                                DecimalField("Longitude", z.longitude, Modifier.weight(1f)) { update(z.copy(longitude = it)) }
                            }
                            Text("Rayon : ${z.radiusMeters.toInt()} m", fontSize = 14.sp)
                            Slider(value = z.radiusMeters.toFloat(), onValueChange = { update(z.copy(radiusMeters = it.toDouble())) }, valueRange = 10f..300f)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = z.allowedAtNight, onCheckedChange = { update(z.copy(allowedAtNight = it)) })
                                Text("Autorisée aussi la nuit", fontSize = 14.sp)
                            }
                        }
                    }
                } }
                OutlinedButton(
                    onClick = { onExtraZones(extraZones + ExtraZone("Zone annexe ${extraZones.size + 1}", defaultCenter.first, defaultCenter.second, 50.0)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Ajouter une zone annexe") }
            }
        }

        Card(colors = panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Surveillance renforcée la nuit", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Zones annexes fermées (sauf exception) et vérification plus fréquente.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = nightEnabled, onCheckedChange = onNightEnabled)
                }
                if (nightEnabled) {
                    Text("Début : ${nightStart} h", fontSize = 14.sp)
                    Slider(value = nightStart.toFloat(), onValueChange = { onNightStart(it.toInt()) }, valueRange = 0f..23f, steps = 22)
                    Text("Fin : ${nightEnd} h", fontSize = 14.sp)
                    Slider(value = nightEnd.toFloat(), onValueChange = { onNightEnd(it.toInt()) }, valueRange = 0f..23f, steps = 22)
                    Text("Vérification la nuit toutes les $nightInterval s", fontSize = 14.sp)
                    Slider(value = nightInterval.toFloat(), onValueChange = { onNightInterval(it.toInt()) }, valueRange = 10f..60f, steps = 9)
                }
            }
        }
    }
}

@Composable
private fun DecimalField(label: String, value: Double, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf("%.6f".format(java.util.Locale.US, value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
