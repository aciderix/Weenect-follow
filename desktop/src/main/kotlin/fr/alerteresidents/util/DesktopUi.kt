package fr.alerteresidents.util

import androidx.compose.ui.graphics.Color
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Boîtes de dialogue Windows natives pour ouvrir / enregistrer un fichier. */
object FileDialogs {
    fun open(title: String, vararg extensions: String): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extensions.isNotEmpty()) {
            d.file = extensions.joinToString(";") { "*.$it" }
            d.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
        }
        d.isVisible = true
        return d.files.firstOrNull()
    }

    fun openImage(): File? = open("Choisir une photo", "jpg", "jpeg", "png", "bmp")

    fun save(title: String, defaultName: String): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.SAVE)
        d.file = defaultName
        d.isVisible = true
        val name = d.file ?: return null
        return File(d.directory, name)
    }
}

/** "#RRGGBB" ou "#AARRGGBB" → couleur Compose. */
fun parseHexColor(hex: String): Color? = runCatching {
    val h = hex.removePrefix("#")
    val v = java.lang.Long.parseLong(h, 16)
    when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}.getOrNull()
