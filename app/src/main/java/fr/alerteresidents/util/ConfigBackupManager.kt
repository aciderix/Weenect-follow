package fr.alerteresidents.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import fr.alerteresidents.data.model.WeenectAccount
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigBackupManager {

    fun createBackupJson(
        facilityZone: FacilityZone,
        residents: List<Resident>,
        exportedBy: String = "Équipe soignante",
        accounts: List<Pair<WeenectAccount, String?>> = emptyList(),
        passphrase: String? = null
    ): String = BackupCodec.createBackupJson(facilityZone, residents, exportedBy, accounts, passphrase)

    fun parseBackupJson(jsonString: String): BackupData? = BackupCodec.parseBackupJson(jsonString)

    fun decryptSecrets(backup: BackupData, passphrase: String): Map<Long, String>? = BackupCodec.decryptSecrets(backup, passphrase)

    fun exportAndShare(context: Context, json: String, facilityName: String, residentCount: Int): Boolean = try {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(Date())
        val sanitized = facilityName.replace(Regex("[^a-zA-Z0-9_]"), "_").take(20)
        val uri = writeExport(context, "config_${sanitized}_$dateStr.json", json)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Configuration Alerte Résidents - $facilityName")
            putExtra(
                Intent.EXTRA_TEXT,
                "Configuration Alerte Résidents pour $facilityName ($residentCount résident(s)). " +
                    "À importer dans l'application sur le nouveau téléphone."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Transférer la configuration").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Écrit un fichier dans le cache d'export et renvoie son URI partageable. */
    fun writeExport(context: Context, fileName: String, content: String): Uri =
        writeExportBytes(context, fileName, content.toByteArray(Charsets.UTF_8))

    fun writeExportBytes(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun readJsonFromUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
