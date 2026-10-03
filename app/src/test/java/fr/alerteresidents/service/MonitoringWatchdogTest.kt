package fr.alerteresidents.service

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import fr.alerteresidents.ui.screens.aggressiveOemGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonitoringWatchdogTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Test
    fun `chien de garde arme une seule alarme qui reveille le telephone`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        MonitoringWatchdog.schedule(app)
        MonitoringWatchdog.schedule(app)
        assertEquals(1, alarms.scheduledAlarms.size)
        val alarm = alarms.peekNextScheduledAlarm()!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        val delay = alarm.triggerAtMs - SystemClock.elapsedRealtime()
        assertTrue("délai $delay", delay in (MonitoringWatchdog.INTERVAL_MS - 1_000)..MonitoringWatchdog.INTERVAL_MS)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test
    fun `sans autorisation d alarme exacte - alarme approximative quand meme armee`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        MonitoringWatchdog.schedule(app, 5_000L)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(alarms.peekNextScheduledAlarm()!!.isAllowWhileIdle)
    }

    @Test
    fun `alarme recue - relance la surveillance et rearme la suivante`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        WatchdogReceiver().onReceive(app, Intent(MonitoringWatchdog.ACTION_WATCHDOG))
        assertEquals(1, alarms.scheduledAlarms.size)
        val started = shadowOf(app).nextStartedService
        assertEquals(ResidentMonitoringService::class.java.name, started.component?.className)
        assertEquals(ResidentMonitoringService.ACTION_START, started.action)
    }

    @Test
    fun `guide fabricant pour les marques qui tuent les applis`() {
        assertEquals("https://dontkillmyapp.com/xiaomi", aggressiveOemGuide("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/samsung", aggressiveOemGuide("samsung"))
        assertEquals("https://dontkillmyapp.com/oneplus", aggressiveOemGuide("OnePlus"))
        assertNull(aggressiveOemGuide("Google"))
    }
}
