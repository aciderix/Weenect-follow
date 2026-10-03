package fr.alerteresidents.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/** Photos des résidents : copiées et réduites dans le stockage privé de l'app. */
object PhotoStore {
    private const val MAX_SIZE = 512
    private val cache = LruCache<String, Bitmap>(40)

    /** Copie l'image choisie et renvoie le chemin du fichier enregistré (ou null). */
    fun importPhoto(context: Context, source: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE && bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Image illisible")
        val scale = MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        ) else bitmap
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "resident_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        cache.get(path)?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)?.also { cache.put(path, it) }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        cache.remove(path)
        runCatching { File(path).delete() }
    }
}
