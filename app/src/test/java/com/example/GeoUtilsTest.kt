package com.example

import com.example.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun testHaversineDistanceZeroForSamePoint() {
        val dist = GeoUtils.calculateDistanceMeters(48.8566, 2.3522, 48.8566, 2.3522)
        assertEquals(0.0, dist, 0.01)
    }

    @Test
    fun testHaversineDistanceKnownPoints() {
        // Paris center (48.8566, 2.3522) to Tour Eiffel (48.8584, 2.2945) ≈ 4.2 km
        val dist = GeoUtils.calculateDistanceMeters(48.8566, 2.3522, 48.8584, 2.2945)
        assertTrue("Distance should be between 4000m and 4500m, got: $dist", dist in 4000.0..4500.0)
    }

    @Test
    fun testFormatDistance() {
        assertEquals("45 m", GeoUtils.formatDistance(45.2))
        assertEquals("999 m", GeoUtils.formatDistance(999.0))
        assertEquals("1.5 km", GeoUtils.formatDistance(1500.0))
    }

    @Test
    fun testBearingCardinal() {
        // Direct north
        val cardinalNorth = GeoUtils.bearingToCardinal(0.0)
        assertEquals("Nord", cardinalNorth)

        // Direct east
        val cardinalEast = GeoUtils.bearingToCardinal(90.0)
        assertEquals("Est", cardinalEast)

        // Direct south
        val cardinalSouth = GeoUtils.bearingToCardinal(180.0)
        assertEquals("Sud", cardinalSouth)

        // Direct west
        val cardinalWest = GeoUtils.bearingToCardinal(270.0)
        assertEquals("Ouest", cardinalWest)
    }
}
