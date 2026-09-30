package com.example.util

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.MainActivity
import com.example.data.model.Resident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SoundAlertManager(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null
    private var isPlaying = false
    private var originalAlarmVolume: Int? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val notificationHelper = NotificationHelper(context)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Déclenche une alerte d'urgence majeure :
     * - Réveil physique de l'écran (Screen WakeLock)
     * - Sonnerie d'alarme continue + BIP-BIP d'urgence répétitif en boucle
     * - Volume d'alarme forcé à 100%
     * - Vibration forte cadencée
     * - Notification flottante plein écran (Heads-Up / FullScreenIntent)
     */
    fun playZoneExitAlarm(
        resident: Resident? = null,
        distanceMeters: Double = 0.0,
        soundEnabled: Boolean = true,
        vibrateEnabled: Boolean = true,
        forceMaxVolume: Boolean = true
    ) {
        if (isPlaying) {
            if (resident != null) {
                notificationHelper.showEmergencyNotification(resident, distanceMeters)
            }
            return
        }
        isPlaying = true

        // 1. Allumer l'écran du téléphone même s'il est verrouillé ou en veille
        wakeUpScreen()

        // 2. Afficher la notification prioritaire plein écran / bandeau flottant
        if (resident != null) {
            notificationHelper.showEmergencyNotification(resident, distanceMeters)
        }

        // 3. Tenter d'ouvrir directement l'écran d'urgence au premier plan
        launchEmergencyScreen(resident)

        // 4. Forcer le volume d'alarme au niveau maximal (100%)
        if (soundEnabled && forceMaxVolume) {
            try {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                originalAlarmVolume = currentVol
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            } catch (e: Exception) {
                Log.w("SoundAlertManager", "Could not boost volume: ${e.message}")
            }
        }

        // 5. Déclencher le son d'alarme et les BIPs d'urgence
        if (soundEnabled) {
            startEmergencyAudio()
        }

        // 6. Vibration forte cadencée
        if (vibrateEnabled) {
            startVibration()
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenWakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlerteResidents:EmergencyWakeUpLock"
            )
            screenWakeLock?.acquire(10000L) // Garde l'écran allumé pendant 10s
        } catch (e: Exception) {
            Log.w("SoundAlertManager", "Screen wake lock error: ${e.message}")
        }
    }

    private fun launchEmergencyScreen(resident: Resident?) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_ACTION", "VIEW_ALERT")
                if (resident != null) {
                    putExtra("EXTRA_RESIDENT_ID", resident.id)
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("SoundAlertManager", "Could not launch MainActivity over lockscreen: ${e.message}")
        }
    }

    private fun startEmergencyAudio() {
        // A. Jouer la sonnerie d'alarme système
        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            if (alarmUri != null) {
                ringtone = RingtoneManager.getRingtone(context, alarmUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e("SoundAlertManager", "Ringtone error: ${e.message}")
        }

        // B. Générateur de bips cadencés d'urgence (BIP-BIP-BIP médical garanti)
        toneJob?.cancel()
        toneJob = coroutineScope.launch {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                while (isActive && isPlaying) {
                    // Émet un bip strident d'urgence pendant 1,5 seconde
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
                    delay(2000L)
                }
            } catch (e: Exception) {
                Log.e("SoundAlertManager", "ToneGenerator loop error: ${e.message}")
            }
        }
    }

    private fun startVibration() {
        try {
            val timings = longArrayOf(0, 600, 200, 600, 200, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0)) // 0 = boucle infinie
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, 0)
            }
        } catch (e: Exception) {
            Log.e("SoundAlertManager", "Vibrate error: ${e.message}")
        }
    }

    /**
     * Arrête immédiatement la sonnerie, les bips, la vibration et restaure le volume d'origine.
     */
    fun stopAlarm() {
        isPlaying = false

        toneJob?.cancel()
        toneJob = null

        try {
            ringtone?.stop()
            ringtone = null
        } catch (_: Exception) {}

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        // Restaurer le volume d'alarme initial
        originalAlarmVolume?.let { prevVol ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
            } catch (_: Exception) {}
            originalAlarmVolume = null
        }

        notificationHelper.dismissAllAlertNotifications()
    }
}
