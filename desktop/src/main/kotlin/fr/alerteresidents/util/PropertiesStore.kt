package fr.alerteresidents.util

import fr.alerteresidents.cloud.KeyValueStore
import java.io.File
import java.util.Properties

/** Réglages du partage entre appareils (fichier partage.properties ; le jeton de session y est chiffré). */
class PropertiesStore(private val file: File) : KeyValueStore {
    private val props = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }

    override fun get(key: String): String? = synchronized(props) { props.getProperty(key) }

    override fun put(key: String, value: String?) = synchronized(props) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        runCatching { file.outputStream().use { props.store(it, "Alerte Résidents — partage entre appareils") } }
        Unit
    }
}
