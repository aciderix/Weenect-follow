package fr.alerteresidents.desktop.platform

import com.sun.jna.platform.win32.Kernel32
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.FileHandler
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Emplacements et intégrations propres à Windows (avec repli pour le développement sous Linux/macOS). */
object Platform {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Données de l'application : %LOCALAPPDATA%\AlerteResidents (surchargeable pour les tests). */
    val dataDir: File by lazy {
        val override = System.getProperty("alerteresidents.dataDir")
        val dir = when {
            override != null -> File(override)
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "AlerteResidents")
            else -> File(System.getProperty("user.home"), ".alerte-residents")
        }
        dir.apply { mkdirs() }
    }

    /** Chemin de l'exécutable installé (fourni par jpackage) ; null en développement. */
    val installedExe: String? get() = System.getProperty("jpackage.app-path")

    fun setupLogging() {
        runCatching {
            val handler = FileHandler(File(dataDir, "journal-technique.%g.log").path, 2_000_000, 3, true)
            handler.formatter = SimpleFormatter()
            Logger.getLogger("").addHandler(handler)
        }
    }

    /**
     * Empêche la mise en veille automatique (à rappeler régulièrement). [display] rallume / garde
     * l'écran allumé, utilisé pendant une alarme.
     */
    fun keepAwake(display: Boolean = false) {
        if (!isWindows) return
        runCatching {
            val flags = 0x00000001 or (if (display) 0x00000002 else 0) // ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
            Kernel32.INSTANCE.SetThreadExecutionState(flags)
        }
    }

    /** Monte le volume général au maximum (touches multimédia, réactive aussi le son coupé). */
    fun forceMaxVolume() {
        if (!isWindows) return
        runCatching {
            ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "\$w = New-Object -ComObject WScript.Shell; 1..50 | ForEach-Object { \$w.SendKeys([char]175) }"
            ).redirectErrorStream(true).start()
        }
    }

    fun run(vararg cmd: String, timeoutSec: Long = 10): Int = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.exitValue() else -1
    }.getOrDefault(-1)
}

/** Lancement automatique à l'ouverture de la session Windows (clé Run de l'utilisateur, sans droits admin). */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "AlerteResidents"

    /** Possible uniquement pour la version installée (l'exécutable doit exister). */
    val isSupported: Boolean get() = Platform.isWindows && Platform.installedExe != null

    fun isEnabled(): Boolean = isSupported && Platform.run("reg", "query", KEY, "/v", NAME) == 0

    fun setEnabled(enabled: Boolean): Boolean {
        if (!isSupported) return false
        return if (enabled) {
            Platform.run("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"${Platform.installedExe}\" --minimized", "/f") == 0
        } else {
            Platform.run("reg", "delete", KEY, "/v", NAME, "/f") == 0
        }
    }
}
