package fr.alerteresidents.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sortie accompagnée (famille, activité, rendez-vous) pour un ou plusieurs résidents :
 * la surveillance de zone est suspendue pour la durée choisie puis reprend toute seule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutingDialog(
    residentNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (durationMinutes: Int, reason: String) -> Unit
) {
    val durations = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 180 to "3 h", 240 to "4 h", 480 to "8 h")
    var duration by remember { mutableIntStateOf(120) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sortie accompagnée", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (residentNames.size == 1) residentNames.first() else "${residentNames.size} résidents : ${residentNames.joinToString(", ")}",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Text(
                    "Aucune alerte de sortie pendant la durée choisie. La surveillance reprend automatiquement ensuite : " +
                        "si le résident n'est pas rentré, l'alarme se déclenchera.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    durations.forEach { (min, label) ->
                        FilterChip(selected = duration == min, onClick = { duration = min }, label = { Text(label, fontSize = 14.sp) })
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(60) },
                    label = { Text("Motif (facultatif)") },
                    placeholder = { Text("Sortie famille, activité…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(duration, reason.trim()) }) { Text("Démarrer la sortie") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
