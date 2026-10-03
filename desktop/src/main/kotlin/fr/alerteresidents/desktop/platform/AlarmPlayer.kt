package fr.alerteresidents.desktop.platform

import fr.alerteresidents.util.AlarmSound
import java.io.BufferedInputStream
import java.io.File
import java.util.logging.Logger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl

/** Son d'alarme en boucle (sirène intégrée et/ou alarme Windows), gain au maximum. */
class AlarmPlayer(private val soundChoice: () -> AlarmSound, private val forceVolume: () -> Boolean) {
    private val log = Logger.getLogger("AlarmPlayer")
    private val clips = mutableListOf<Clip>()
    private var beepThread: Thread? = null

    @Volatile var isPlaying = false
        private set

    @Synchronized
    fun start() {
        if (isPlaying) return
        isPlaying = true
        if (forceVolume()) Platform.forceMaxVolume()
        val started = when (soundChoice()) {
            AlarmSound.SIREN -> startSiren() || startWindowsAlarm()
            AlarmSound.PHONE -> startWindowsAlarm() || startSiren()
            AlarmSound.BOTH -> startSiren() or startWindowsAlarm()
        }
        if (!started) startBeeps()
    }

    private fun play(open: () -> javax.sound.sampled.AudioInputStream): Boolean = try {
        val clip = AudioSystem.getClip()
        open().use { clip.open(it) }
        (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.let { it.value = it.maximum }
        clip.loop(Clip.LOOP_CONTINUOUSLY)
        clips.add(clip)
        true
    } catch (e: Throwable) {
        log.warning("Lecture impossible : ${e.message}")
        false
    }

    private fun startSiren() = play {
        AudioSystem.getAudioInputStream(BufferedInputStream(javaClass.getResourceAsStream("/alarme_sirene.wav")!!))
    }

    /** Sons d'alarme fournis avec Windows 10 / 11. */
    private fun startWindowsAlarm(): Boolean {
        val media = File(System.getenv("WINDIR") ?: "C:\\Windows", "Media")
        val file = listOf("Alarm01.wav", "Alarm02.wav", "Alarm03.wav").map { File(media, it) }.firstOrNull { it.exists() } ?: return false
        return play { AudioSystem.getAudioInputStream(file) }
    }

    /** Dernier recours : bips système. */
    private fun startBeeps() {
        beepThread = Thread {
            while (isPlaying) {
                runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
                Thread.sleep(700)
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        clips.forEach { runCatching { it.stop(); it.close() } }
        clips.clear()
        beepThread = null
    }
}
