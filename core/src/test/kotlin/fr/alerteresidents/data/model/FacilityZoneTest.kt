package fr.alerteresidents.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityZoneTest {

    @Test
    fun `polygon points survive an encode decode round trip`() {
        val points = listOf(47.1787 to -1.6192, 47.1790 to -1.6180, 47.1780 to -1.6175)
        val zone = FacilityZone(zoneType = "POLYGON", polygonPointsJson = FacilityZone.encodePolygonPoints(points))

        assertEquals(points, zone.getPolygonPoints())
    }

    @Test
    fun `blank or malformed polygon json yields no points`() {
        assertTrue(FacilityZone(polygonPointsJson = "").getPolygonPoints().isEmpty())
        assertTrue(FacilityZone(polygonPointsJson = "not json").getPolygonPoints().isEmpty())
    }

    @Test
    fun `default zone is an active circle`() {
        val zone = FacilityZone()
        assertEquals("CIRCLE", zone.zoneType)
        assertTrue(zone.isZoneActive)
        assertTrue(zone.radiusMeters > 0)
    }
}
