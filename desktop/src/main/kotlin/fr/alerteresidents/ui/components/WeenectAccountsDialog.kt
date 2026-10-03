package fr.alerteresidents.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.data.model.WeenectAccount
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import kotlinx.coroutines.launch

/**
 * Comptes Weenect de l'établissement. Souvent un seul compte porte toutes les balises :
 * on s'y connecte une fois, puis chaque résident choisit sa balise dans la liste.
 */
@Composable
fun WeenectAccountsDialog(viewModel: ResidentViewModel, onDismiss: () -> Unit) {
    val accounts by viewModel.accounts.collectAsState()
    val residents by viewModel.residents.collectAsState()
    var editing by remember { mutableStateOf<WeenectAccount?>(null) }
    var creating by remember { mutableStateOf(accounts.isEmpty()) }
    var toDelete by remember { mutableStateOf<WeenectAccount?>(null) }

    if (creating || editing != null) {
        AccountEditor(
            existing = editing,
            viewModel = viewModel,
            onDone = { creating = false; editing = null }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comptes Weenect", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Les mots de passe sont chiffrés sur ce poste (protection Windows) et ne sont jamais affichés.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                accounts.forEach { a ->
                    val used = residents.count { it.accountId == a.id }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(a.username, fontSize = 13.sp)
                                Text("$used résident(s) associé(s)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = a }) { Icon(Icons.Default.Edit, contentDescription = "Modifier ${a.label}") }
                            IconButton(onClick = { toDelete = a }) { Icon(Icons.Default.Delete, contentDescription = "Supprimer ${a.label}", tint = AppStatusColors.danger) }
                        }
                    }
                }
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Ajouter un compte") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer le compte ${a.label} ?") },
            text = { Text("Les résidents associés à ce compte ne seront plus suivis tant qu'un autre compte ne leur est pas attribué.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteAccount(a); toDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AppStatusColors.dangerSolid, contentColor = androidx.compose.ui.graphics.Color.White)) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun AccountEditor(existing: WeenectAccount?, viewModel: ResidentViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Nouveau compte Weenect" else "Modifier ${existing.label}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Nom du compte (ex. « Balises MAS »)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it; success = false }, label = { Text("E-mail Weenect") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; success = false }, label = { Text(if (existing == null) "Mot de passe" else "Nouveau mot de passe") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("Chiffré avec la protection de session Windows", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    enabled = username.isNotBlank() && password.isNotBlank() && !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            viewModel.testWeenectCredentials(username, password).fold(
                                { result = "✔ Connexion réussie : ${it.size} balise(s) — ${it.joinToString { t -> t.name ?: t.id.toString() }}"; success = true },
                                { result = "✘ ${it.message}"; success = false }
                            )
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Tester la connexion")
                }
                result?.let {
                    Text(it, fontSize = 14.sp, color = if (success) AppStatusColors.safe else AppStatusColors.danger, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = success,
                onClick = { viewModel.saveAccount(label, username, password, existing?.id); onDone() }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Annuler") } }
    )
}
