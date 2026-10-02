package fr.alerteresidents.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.alerteresidents.R
import fr.alerteresidents.data.model.FacilityZone
import fr.alerteresidents.data.model.Resident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SoundAlertManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `bundled siren is packaged as an uncompressed raw resource`() {
        context.resources.openRawResource(R.raw.alarme_sirene).use { stream ->
            assertTrue("sirène intégrée trop petite", stream.readBytes().size > 100_000)
        }
    }

    @Test
    fun `every sound choice starts and stops the alarm`() {
        for (choice in AlarmSound.entries) {
            val manager = SoundAlertManager(context, soundChoice = { choice })
            val zone = FacilityZone()
            manager.playZoneExitAlarm(Resident(id = 1, name = "A"), zone)
            manager.playZoneExitAlarm(Resident(id = 2, name = "B"), zone)
            assertEquals("$choice", setOf(1L, 2L), manager.activeAlarms.value.keys)
            assertEquals(1, manager.stopAlarm(1))
            assertTrue(manager.isAlarmPlaying)
            assertEquals(0, manager.stopAlarm(null))
            assertFalse(manager.isAlarmPlaying)
        }
    }

    @Test
    fun `default choice is the siren`() {
        assertEquals(AlarmSound.SIREN, AppPreferences(context).alarmSound.value)
        AppPreferences(context).setAlarmSound(AlarmSound.BOTH)
        assertEquals(AlarmSound.BOTH, AppPreferences(context).alarmSound.value)
    }
}
