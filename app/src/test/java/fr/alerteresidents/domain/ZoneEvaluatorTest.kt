package fr.alerteresidents.domain

import android.app.Application
import fr.alerteresidents.data.model.ExtraZone
import fr.alerteresidents.data.model.FacilityZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ZoneEvaluatorTest {
    private val circle = FacilityZone(centerLatitude = 47.0, centerLongitude = -1.0, radiusMeters = 150.0)

    @Test
    fun `center is inside`() {
        val e = ZoneEvaluator.evaluate(circle, 47.0, -1.0, null, 12)
        assertTrue(e.inside)
        assertEquals(0.0, e.metersBeyondBoundary, 0.0)
    }

    @Test
    fun `far point is clearly outside`() {
        val e = ZoneEvaluator.evaluate(circle, 47.005, -1.0, 20, 12)
        assertFalse(e.inside)
        assertTrue(e.clearlyOutside)
        assertEquals(556.0 - 150.0, e.metersBeyondBoundary, 2.0)
    }

    @Test
    fun `point just outside but within accuracy is not clearly outside`() {
        val e = ZoneEvaluator.evaluate(circle, 47.0015, -1.0, 30, 12) // ≈ 167 m
        assertFalse(e.inside)
        assertFalse(e.clearlyOutside)
    }

    @Test
    fun `inactive zone is always inside`() {
        assertTrue(ZoneEvaluator.evaluate(circle.copy(isZoneActive = false), 48.0, 2.0, null, 12).inside)
    }

    @Test
    fun `polygon zone reports the distance to its border`() {
        val square = listOf(46.9995 to -1.0005, 46.9995 to -0.9995, 47.0005 to -0.9995, 47.0005 to -1.0005)
        val zone = circle.copy(zoneType = "POLYGON", polygonPointsJson = FacilityZone.encodePolygonPoints(square))
        val e = ZoneEvaluator.evaluate(zone, 47.001, -1.0, null, 12)
        assertFalse(e.inside)
        assertEquals(55.6, e.distanceMeters, 1.0)
        assertTrue(ZoneEvaluator.evaluate(zone, 47.0, -1.0, null, 12).inside)
    }

    @Test
    fun `annex zones extend the allowed area depending on the hour`() {
        val zone = circle.copy(
            extraZonesJson = FacilityZone.encodeExtraZones(
                listOf(ExtraZone("Jardin", 47.005, -1.0, 80.0, allowedAtNight = false), ExtraZone("Cour", 46.995, -1.0, 80.0, allowedAtNight = true))
            ),
            nightModeEnabled = true, nightStartHour = 21, nightEndHour = 7
        )
        assertTrue("jardin le jour", ZoneEvaluator.evaluate(zone, 47.005, -1.0, null, 14).inside)
        assertFalse("jardin la nuit", ZoneEvaluator.evaluate(zone, 47.005, -1.0, null, 23).inside)
        assertTrue("cour autorisée la nuit", ZoneEvaluator.evaluate(zone, 46.995, -1.0, null, 2).inside)
    }

    @Test
    fun `night range wraps around midnight`() {
        val z = FacilityZone(nightModeEnabled = true, nightStartHour = 21, nightEndHour = 7)
        assertTrue(z.isNight(22)); assertTrue(z.isNight(3)); assertFalse(z.isNight(7)); assertFalse(z.isNight(15))
        assertFalse(FacilityZone(nightModeEnabled = false).isNight(23))
        assertEquals(10, z.copy(nightRefreshIntervalSeconds = 10, refreshIntervalSeconds = 30).refreshIntervalFor(23))
        assertEquals(30, z.copy(nightRefreshIntervalSeconds = 10, refreshIntervalSeconds = 30).refreshIntervalFor(12))
    }

    @Test
    fun `extra zones survive an encode decode round trip`() {
        val zones = listOf(ExtraZone("Jardin", 47.1, -1.6, 60.0, true))
        assertEquals(zones, FacilityZone(extraZonesJson = FacilityZone.encodeExtraZones(zones)).getExtraZones())
        assertTrue(FacilityZone(extraZonesJson = "pas du json").getExtraZones().isEmpty())
    }
}
