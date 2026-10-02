package fr.alerteresidents.util

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import fr.alerteresidents.MainActivity
import fr.alerteresidents.R
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Alarme d'urgence : son continu sur le canal ALARM, vibration et notification plein écran.
 * Plusieurs résidents peuvent être en alarme en même temps : le son s'arrête quand la dernière
 * alarme est coupée. Thread-safe (appelée depuis le service et depuis l'interface).
 */
class SoundAlertManager(
    private val context: Context,
    val notificationHelper: NotificationHelper = NotificationHelper(context),
    /** Son choisi dans les Paramètres (lu à chaque déclenchement). */
    private val soundChoice: () -> AlarmSound = { AlarmSound.SIREN }
) {
    private val lock = Any()
    private val players = mutableListOf<MediaPlayer>()
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null
    private var soundPlaying = false
    private var originalAlarmVolume: Int? = null
    private var alarmWakeLock: PowerManager.WakeLock? = null

    private val _activeAlarms = MutableStateFlow<Map<Long, AlarmInfo>>(emptyMap())
    /** Alarmes en cours, par résident. Source unique de vérité pour l'interface. */
    val activeAlarms: StateFlow<Map<Long, AlarmInfo>> = _activeAlarms.asStateFlow()

    val isAlarmPlaying: Boolean get() = _activeAlarms.value.isNotEmpty()

    fun isRinging(residentId: Long): Boolean = _activeAlarms.value.containsKey(residentId)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Déclenche (ou rejoue pour un rappel) l'alarme de sortie de zone d'un résident.
     * Les réglages son / vibration de la zone sont respectés ; le volume d'alarme est forcé au maximum.
     */
    fun playZoneExitAlarm(
        resident: Resident,
        zone: FacilityZone,
        isDrill: Boolean = false,
        isReminder: Boolean = false
    ) {
        val startSound: Boolean
        synchronized(lock) {
            val info = AlarmInfo(resident.id, resident.name, isDrill, isReminder, System.currentTimeMillis())
            _activeAlarms.value = _activeAlarms.value + (resident.id to info)
            startSound = !soundPlaying
            soundPlaying = true
        }
        notificationHelper.showEmergencyNotification(resident, zone, isDrill, isReminder)
        if (!startSound) return

        acquireAlarmWakeLock()
        launchEmergencyScreen(resident.id)
        if (zone.soundAlertsEnabled) {
            boostVolume()
            startEmergencyAudio()
        }
        if (zone.vibrateAlertsEnabled || !zone.soundAlertsEnabled) {
            // Sans son, la vibration est toujours active : une alerte doit rester perceptible.
            startVibration()
        }
    }

    /** Test manuel depuis les Paramètres (sans résident). */
    fun playTestAlarm(zone: FacilityZone) {
        playZoneExitAlarm(
            Resident(id = TEST_ALARM_ID, name = "Test d'alarme", roomNumber = "Exercice"),
            zone.copy(soundAlertsEnabled = true, vibrateAlertsEnabled = true),
            isDrill = true
        )
    }

    private fun boostVolume() {
        try {
            synchronized(lock) {
                if (originalAlarmVolume == null) {
                    originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                }
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost volume: ${e.message}")
        }
    }

    private fun acquireAlarmWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            synchronized(lock) {
                if (alarmWakeLock == null) {
                    alarmWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlerteResidents:EmergencyAlarm")
                        ?.apply { setReferenceCounted(false) }
                }
            }
            alarmWakeLock?.acquire(10 * 60_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm wake lock error: ${e.message}")
        }
    }

    /**
     * Ramène l'application au premier plan si elle est déjà visible. En arrière-plan, Android
     * bloque ce lancement : c'est la notification plein écran qui réveille l'écran.
     */
    private fun launchEmergencyScreen(residentId: Long) {
        try {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_VIEW_ALERT)
                putExtra(MainActivity.EXTRA_RESIDENT_ID, residentId)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch MainActivity: ${e.message}")
        }
    }

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Sirène intégrée (res/raw), jouée en boucle sur le canal Alarme. */
    private fun startSiren(): Boolean = try {
        val afd = context.resources.openRawResourceFd(R.raw.alarme_sirene)
        val player = MediaPlayer().apply {
            afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setAudioAttributes(alarmAttributes)
            isLooping = true
            prepare()
            start()
        }
        players.add(player)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Siren failed: ${e.message}")
        false
    }

    /** Sonnerie d'alarme du téléphone (MediaPlayer, sinon Ringtone). */
    private fun startPhoneAlarm(): Boolean {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false
        try {
            val player = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(alarmAttributes)
                isLooping = true
                prepare()
                start()
            }
            players.add(player)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Phone alarm MediaPlayer failed, trying Ringtone: ${e.message}")
        }
        return try {
            ringtone = RingtoneManager.getRingtone(context, alarmUri)?.apply {
                audioAttributes = alarmAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            ringtone != null
        } catch (e: Exception) {
            Log.w(TAG, "Ringtone fallback failed: ${e.message}")
            false
        }
    }

    private fun startEmergencyAudio() {
        synchronized(lock) {
            val started = when (soundChoice()) {
                AlarmSound.SIREN -> startSiren() || startPhoneAlarm()
                AlarmSound.PHONE -> startPhoneAlarm() || startSiren()
                AlarmSound.BOTH -> startSiren() or startPhoneAlarm()
            }
            if (started) return
            // Dernier recours : bips d'urgence
            toneJob?.cancel()
            toneJob = scope.launch {
                try {
                    val generator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    synchronized(lock) { toneGenerator = generator }
                    while (isActive && isAlarmPlaying) {
                        generator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
                        delay(2000L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ToneGenerator loop error: ${e.message}")
                }
            }
        }
    }

    private fun startVibration() {
        try {
            val timings = longArrayOf(0, 600, 200, 600, 200, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error: ${e.message}")
        }
    }

    /**
     * Coupe l'alarme d'un résident (ou toutes si [residentId] est null). Le son ne s'arrête que
     * lorsque plus aucune alarme n'est active. Renvoie le nombre d'alarmes encore actives.
     */
    fun stopAlarm(residentId: Long? = null): Int {
        val remaining: Int
        synchronized(lock) {
            _activeAlarms.value = if (residentId == null) emptyMap() else _activeAlarms.value - residentId
            remaining = _activeAlarms.value.size
            if (remaining > 0) {
                // Les notifications restent ; seule celle du résident concerné disparaît.
                residentId?.let { notificationHelper.dismissEmergencyNotification(it) }
                return remaining
            }
            soundPlaying = false
            toneJob?.cancel()
            toneJob = null
            players.forEach { p -> runCatching { p.stop() }; runCatching { p.release() } }
            players.clear()
            runCatching { ringtone?.stop() }
            ringtone = null
            runCatching { toneGenerator?.stopTone(); toneGenerator?.release() }
            toneGenerator = null
            runCatching { vibrator?.cancel() }
            runCatching { if (alarmWakeLock?.isHeld == true) alarmWakeLock?.release() }
            originalAlarmVolume?.let { prev ->
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0) }
            }
            originalAlarmVolume = null
        }
        if (residentId == null) notificationHelper.dismissAllAlertNotifications()
        else notificationHelper.dismissEmergencyNotification(residentId)
        return 0
    }

    companion object {
        private const val TAG = "SoundAlertManager"
        const val TEST_ALARM_ID = -1L
    }
}
