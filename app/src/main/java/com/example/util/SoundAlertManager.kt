package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.data.model.Resident

class SoundAlertManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var isPlaying = false
    private var originalAlarmVolume: Int? = null

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
     * Déclenche une sonnerie d'urgence forte, continue (en boucle) avec vibration
     * et notification système prioritaire pour alerter le personnel.
     */
    fun playZoneExitAlarm(
        resident: Resident? = null,
        distanceMeters: Double = 0.0,
        soundEnabled: Boolean = true,
        vibrateEnabled: Boolean = true,
        forceMaxVolume: Boolean = true
    ) {
        if (isPlaying) {
            // Déjà en cours de sonnerie, mais mettons à jour la notification si nouveau résident
            if (resident != null) {
                notificationHelper.showEmergencyNotification(resident, distanceMeters)
            }
            return
        }
        isPlaying = true

        // 1. Notification système prioritaire
        if (resident != null) {
            notificationHelper.showEmergencyNotification(resident, distanceMeters)
        }

        // 2. Forcer le volume d'alarme au niveau maximal (100%)
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

        // 3. Sonnerie forte en boucle continue via MediaPlayer
        if (soundEnabled) {
            try {
                var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }

                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, alarmUri!!)
                    isLooping = true // Sonnerie continue jusqu'à acquittement
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("SoundAlertManager", "Failed MediaPlayer alarm, falling back to tone generator", e)
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15000)
                } catch (t: Throwable) {
                    Log.e("SoundAlertManager", "Fallback tone failed", t)
                }
            }
        }

        // 4. Vibration forte en boucle (modèle avertissement sécurité)
        if (vibrateEnabled) {
            try {
                val timings = longArrayOf(0, 800, 400, 800, 400, 1200)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0)) // 0 = boucle infinie
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(timings, 0)
                }
            } catch (e: Exception) {
                Log.e("SoundAlertManager", "Vibrate error", e)
            }
        }
    }

    /**
     * Arrête immédiatement la sonnerie, la vibration et restaure le volume d'origine.
     */
    fun stopAlarm() {
        isPlaying = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        // Restaurer le volume d'alarme initial si modifié
        originalAlarmVolume?.let { prevVol ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
            } catch (_: Exception) {}
            originalAlarmVolume = null
        }

        notificationHelper.dismissAllAlertNotifications()
    }
}
