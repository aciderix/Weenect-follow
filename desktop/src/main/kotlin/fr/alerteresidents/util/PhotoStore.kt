package fr.alerteresidents.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import fr.alerteresidents.desktop.platform.Platform
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Photos des résidents : copiées et réduites dans le dossier de données du poste. */
object PhotoStore {
    private const val MAX_SIZE = 512
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun importPhoto(source: File): String? = try {
        val img = ImageIO.read(source) ?: throw IllegalArgumentException("Image illisible")
        val scale = minOf(1.0, MAX_SIZE.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        val dir = File(Platform.dataDir, "photos").apply { mkdirs() }
        val file = File(dir, "resident_${System.currentTimeMillis()}.jpg")
        ImageIO.write(out, "jpg", file)
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    fun load(path: String?): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        cache[path]?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        return runCatching { ImageIO.read(f)?.toComposeImageBitmap() }.getOrNull()?.also { cache[path] = it }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        cache.remove(path)
        runCatching { File(path).delete() }
    }
}
