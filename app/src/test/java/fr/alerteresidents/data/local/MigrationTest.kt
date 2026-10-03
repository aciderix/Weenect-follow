package fr.alerteresidents.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La mise à jour v3 → v4 conserve résidents, zone et journal (plus de migration destructive). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate3To4KeepsData() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO residents (id, name, roomNumber, photoUri, avatarColorHex, weenectUsername, weenectPassword, trackerId, " +
                    "trackerName, lastLatitude, lastLongitude, lastBattery, lastSpeed, lastUpdatedTime, isInZone, distanceFromCenterMeters, " +
                    "emergencyContact, notes, isTrackingActive) VALUES " +
                    "(1, 'Jean', 'Ch. 1', NULL, '#000000', 'u@test.fr', 'pwd', 42, 'B', 47.0, -1.0, 80, 0.0, 123, 0, 300.0, '', '', 1)"
            )
            db.execSQL(
                "INSERT INTO facility_zone (id, name, address, centerLatitude, centerLongitude, radiusMeters, isZoneActive, zoneType, " +
                    "polygonPointsJson, soundAlertsEnabled, vibrateAlertsEnabled, refreshIntervalSeconds) VALUES " +
                    "(1, 'MAS', 'adr', 47.0, -1.0, 150.0, 1, 'CIRCLE', '', 1, 1, 15)"
            )
            db.execSQL(
                "INSERT INTO alert_events (id, residentId, residentName, timestamp, alertType, latitude, longitude, distanceMeters, " +
                    "isAcknowledged, acknowledgedBy) VALUES (1, 1, 'Jean', 1, 'ENTER_ZONE', 0, 0, 0, 0, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, AppDatabase.MIGRATION_3_4)

        db.query("SELECT name, weenectPassword, isInZone, alertState, lastUpdatedTime, riskLevel FROM residents").use { c ->
            c.moveToFirst()
            assertEquals("Jean", c.getString(0))
            assertEquals("pwd", c.getString(1)) // chiffré ensuite par migrateLegacyCredentials()
            assertEquals(0, c.getInt(2))
            assertEquals("ACTIVE", c.getString(3))
            assertEquals(true, c.isNull(4))
            assertEquals(0, c.getInt(5))
        }
        db.query("SELECT name, nightModeEnabled, extraZonesJson FROM facility_zone").use { c ->
            c.moveToFirst()
            assertEquals("MAS", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        db.query("SELECT isAcknowledged, isDrill FROM alert_events").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals(0, c.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM weenect_accounts").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
