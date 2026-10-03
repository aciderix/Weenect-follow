package fr.alerteresidents.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Une seule instance par session Windows : deux instances feraient sonner deux fois chaque alarme et
 * écriraient en même temps dans les mêmes fichiers. Un second lancement (raccourci, démarrage auto)
 * demande simplement à l'instance déjà ouverte d'afficher sa fenêtre.
 */
class SingleInstance(private val dir: File) {
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private val portFile get() = File(dir, "instance.port")

    /** true si cette instance est la seule ; [onShowRequest] est appelé quand un second lancement survient. */
    fun acquire(onShowRequest: () -> Unit): Boolean {
        val channel = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            channel.close()
            return false
        }
        runCatching {
            val s = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            server = s
            portFile.writeText(s.localPort.toString())
            thread(isDaemon = true, name = "single-instance") {
                while (!s.isClosed) {
                    runCatching { s.accept().use { if (it.getInputStream().read() == SHOW) onShowRequest() } }
                }
            }
        }
        return true
    }

    /** Appelé par un second lancement : réveille l'instance existante. */
    fun signalExisting(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().apply { write(SHOW); flush() } }
        true
    }.getOrDefault(false)

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release(); lock?.channel()?.close() }
    }

    private companion object {
        const val SHOW = 1
    }
}
