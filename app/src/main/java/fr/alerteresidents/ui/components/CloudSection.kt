package fr.alerteresidents.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.alerteresidents.cloud.CloudState
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.ui.viewmodel.SharedConfigPreview
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Partage entre appareils (Supabase). Fichier identique dans l'app Android et l'app Windows :
 * les deux ResidentViewModel exposent la même API (cloud, connectCloud, publishSharedConfig…).
 */

private fun ago(now: Long, at: Long?): String {
    if (at == null) return "jamais"
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "il y a $s s"
        s < 3600 -> "il y a ${s / 60} min"
        else -> SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH).format(Date(at))
    }
}

/** Ligne d'état colorée (réutilisée dans l'écran « État »). */
@Composable
fun CloudStatusLine(cloud: CloudState, now: Long) {
    val (color, text) = when {
        !cloud.configured -> MaterialTheme.colorScheme.outline to "Non configuré : chaque appareil gère ses alertes seul"
        cloud.connected -> AppStatusColors.safe to "Connecté • synchronisé ${ago(now, cloud.lastSyncAt)}"
        cloud.needsLogin -> AppStatusColors.danger to "Reconnexion nécessaire : ${cloud.error ?: ""}"
        else -> AppStatusColors.warning to "Hors ligne (${cloud.error ?: "réseau"}) — les alertes locales fonctionnent"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Appareils connectés au même projet (écran « État » et Paramètres). */
@Composable
fun CloudDevicesList(cloud: CloudState, now: Long) {
    val online = cloud.onlineDevices(now)
    Text(
        "${online.size} appareil(s) en ligne" + if (cloud.devices.size > online.size) " • ${cloud.devices.size - online.size} hors ligne" else "",
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
    )
    cloud.devices.sortedWith(compareByDescending<fr.alerteresidents.cloud.CloudDevice> { it.isThisDevice }.thenBy { it.name }).forEach { d ->
        val isOnline = (d.lastSeenAt ?: 0) > now - CloudState.ONLINE_MS
        val color = when {
            !isOnline -> MaterialTheme.colorScheme.outline
            !d.monitoringOk -> AppStatusColors.warning
            else -> AppStatusColors.safe
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.name + (if (d.isThisDevice) " (cet appareil)" else ""),
                    fontSize = 14.sp, fontWeight = if (d.isThisDevice) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    buildString {
                        append(if (d.platform == "windows") "PC" else if (d.platform == "android") "Téléphone" else "Appareil")
                        append(" • ${d.residentsCount} balise(s) suivie(s)")
                        if (isOnline && !d.monitoringOk) append(" • surveillance dégradée")
                        if (!isOnline) append(" • vu ${ago(now, d.lastSeenAt)}")
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Contenu de la carte « Partage entre appareils » des Paramètres. */
@Composable
fun CloudSyncSection(viewModel: ResidentViewModel) {
    val cloud by viewModel.cloud.collectAsState()
    val now by viewModel.now.collectAsState()
    var showConnect by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }
    var showFetch by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudStatusLine(cloud, now)
        if (!cloud.configured) {
            Text(
                "Reliez les téléphones et PC de l'établissement à un projet Supabase : une sortie détectée par un appareil " +
                    "sonne sur tous, et « Je m'en occupe » coupe l'alarme partout avec le nom du soignant. " +
                    "Mise en place : voir SUPABASE.md dans le dépôt.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { showConnect = true }, modifier = Modifier.fillMaxWidth().testTag("cloud_connect_button")) {
                Text("Connecter à Supabase")
            }
        } else {
            Text("Projet : ${cloud.url}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Compte : ${cloud.email}" + (cloud.displayName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nom de cet appareil : ${cloud.deviceName}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { renaming = true }) { Text("Renommer") }
            }
            if (cloud.pendingChanges > 0) {
                Text("${cloud.pendingChanges} action(s) en attente d'envoi", fontSize = 13.sp, color = AppStatusColors.warning)
            }
            if (cloud.devices.isNotEmpty()) CloudDevicesList(cloud, now)

            Text(
                "Configuration partagée : " + (cloud.configPublishedAt?.let {
                    "publiée par ${cloud.configPublishedBy ?: "?"} le ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date(it))}"
                } ?: "aucune"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPublish = true }, modifier = Modifier.weight(1f)) { Text("Publier ma config.", maxLines = 1) }
                OutlinedButton(
                    onClick = { showFetch = true },
                    enabled = cloud.configPublishedAt != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Récupérer la config.", maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cloud.needsLogin) Button(onClick = { showConnect = true }, modifier = Modifier.weight(1f)) { Text("Se reconnecter") }
                OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.weight(1f)) { Text("Déconnecter") }
            }
        }
    }

    if (showConnect) CloudConnectDialog(viewModel, cloud) { showConnect = false }
    if (showPublish) PublishConfigDialog(viewModel) { showPublish = false }
    if (showFetch) FetchConfigDialog(viewModel) { showFetch = false }
    if (renaming) {
        var name by remember { mutableStateOf(cloud.deviceName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nom de cet appareil") },
            text = {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Ex. PC infirmerie 1er étage") }, singleLine = true)
            },
            confirmButton = { Button(onClick = { viewModel.setCloudDeviceName(name); renaming = false }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } }
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Déconnecter cet appareil ?") },
            text = { Text("Cet appareil continuera de surveiller et de sonner, mais ne partagera plus les alertes avec les autres.") },
            confirmButton = {
                Button(onClick = { viewModel.disconnectCloud(); confirmDisconnect = false }) { Text("Déconnecter") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun CloudConnectDialog(viewModel: ResidentViewModel, cloud: CloudState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(cloud.url.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(cloud.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(cloud.deviceName ?: viewModel.cloudDefaultDeviceName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connexion à Supabase", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Valeurs fournies par l'administrateur (Supabase › Project Settings › API, et le compte créé dans Authentication).",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Adresse du projet") },
                    placeholder = { Text("https://xxxx.supabase.co") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cloud_url"))
                OutlinedTextField(key, { key = it.trim() }, label = { Text("Clé publique (publishable / anon)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cloud_key"))
                OutlinedTextField(email, { email = it.trim() }, label = { Text("E-mail du compte") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(deviceName, { deviceName = it.take(40) }, label = { Text("Nom de cet appareil") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = AppStatusColors.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && url.isNotBlank() && key.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val r = viewModel.connectCloud(url, key, email, password, deviceName)
                        busy = false
                        if (r.isSuccess) onDismiss() else error = r.exceptionOrNull()?.message ?: "Connexion impossible"
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Se connecter")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun PublishConfigDialog(viewModel: ResidentViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val staff by viewModel.prefs.staffName.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = passphrase.length >= 6 && passphrase == confirm

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Publier la configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "La zone, les résidents et les comptes Weenect de cet appareil deviennent la configuration de référence. " +
                        "Les mots de passe Weenect sont chiffrés ici par une phrase secrète avant l'envoi : Supabase ne peut pas les lire. " +
                        "Communiquez la phrase de vive voix aux collègues qui récupéreront la configuration.",
                    fontSize = 13.sp
                )
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Phrase secrète (6 caractères min.)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirmer la phrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    isError = confirm.isNotEmpty() && confirm != passphrase)
                error?.let { Text(it, color = AppStatusColors.danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = viewModel.publishSharedConfig(passphrase, staff.ifBlank { viewModel.cloud.value.deviceName ?: "?" })
                    busy = false
                    if (r.isSuccess) onDismiss() else error = r.exceptionOrNull()?.message
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Publier") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun FetchConfigDialog(viewModel: ResidentViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SharedConfigPreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val r = viewModel.fetchSharedConfig()
        loading = false
        r.onSuccess { preview = it; if (it == null) error = "Aucune configuration publiée" }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Récupérer la configuration partagée", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> CircularProgressIndicator()
                    preview != null -> {
                        val p = preview!!
                        Text(
                            "Publiée par ${p.publishedBy ?: "?"}" + (p.publishedAt?.let { " le " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date(it)) } ?: "") +
                                " : ${p.data.residents.size} résident(s), zone « ${p.data.facilityZone.name} », ${p.data.accounts.size} compte(s) Weenect.",
                            fontSize = 14.sp
                        )
                        if (p.data.hasEncryptedPasswords) {
                            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Phrase secrète") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "« Fusionner » ajoute et met à jour sans rien supprimer ; « Remplacer » efface d'abord les résidents de cet appareil.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error?.let { Text(it, color = AppStatusColors.danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            val p = preview
            if (p != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.importConfiguration(p.data, true, passphrase.ifBlank { null }); onDismiss()
                    }) { Text("Remplacer") }
                    Button(onClick = {
                        viewModel.importConfiguration(p.data, false, passphrase.ifBlank { null }); onDismiss()
                    }) { Text("Fusionner") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
