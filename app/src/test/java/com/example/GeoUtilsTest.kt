package com.example

import com.example.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    // Carré d'environ 110 m x 75 m autour de (47.0, -1.0)
    private val square = listOf(
        46.9995 to -1.0005,
        46.9995 to -0.9995,
        47.0005 to -0.9995,
        47.0005 to -1.0005
    )

    // Polygone concave en « L » : la partie nord-est est exclue
    private val lShape = listOf(
        47.000 to -1.000,
        47.000 to -0.998,
        47.001 to -0.998,
        47.001 to -0.999,
        47.002 to -0.999,
        47.002 to -1.000
    )

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
    fun testHaversineOneThousandthDegreeOfLatitude() {
        // 0.001° de latitude ≈ 111 m partout sur Terre
        val dist = GeoUtils.calculateDistanceMeters(47.0, -1.0, 47.001, -1.0)
        assertEquals(111.2, dist, 0.5)
    }

    @Test
    fun testHaversineIsSymmetric() {
        val a = GeoUtils.calculateDistanceMeters(47.1787, -1.6192, 47.2, -1.55)
        val b = GeoUtils.calculateDistanceMeters(47.2, -1.55, 47.1787, -1.6192)
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun testFormatDistance() {
        assertEquals("45 m", GeoUtils.formatDistance(45.2))
        assertEquals("999 m", GeoUtils.formatDistance(999.0))
        assertTrue(GeoUtils.formatDistance(1500.0) in setOf("1.5 km", "1,5 km"))
    }

    @Test
    fun testBearingCardinal() {
        assertEquals("Nord", GeoUtils.bearingToCardinal(0.0))
        assertEquals("Est", GeoUtils.bearingToCardinal(90.0))
        assertEquals("Sud", GeoUtils.bearingToCardinal(180.0))
        assertEquals("Ouest", GeoUtils.bearingToCardinal(270.0))
        assertEquals("Nord", GeoUtils.bearingToCardinal(359.0))
        assertEquals("Nord-Est", GeoUtils.bearingToCardinal(44.0))
    }

    @Test
    fun testBearingBetweenPoints() {
        assertEquals(0.0, GeoUtils.calculateBearing(47.0, -1.0, 47.01, -1.0), 0.5)
        assertEquals(90.0, GeoUtils.calculateBearing(47.0, -1.0, 47.0, -0.99), 0.5)
        assertEquals(180.0, GeoUtils.calculateBearing(47.0, -1.0, 46.99, -1.0), 0.5)
        assertEquals(270.0, GeoUtils.calculateBearing(47.0, -1.0, 47.0, -1.01), 0.5)
    }

    @Test
    fun testPointInsideSquare() {
        assertTrue(GeoUtils.isPointInPolygon(47.0, -1.0, square))
        assertTrue(GeoUtils.isPointInPolygon(47.0004, -0.9996, square))
    }

    @Test
    fun testPointOutsideSquare() {
        assertFalse(GeoUtils.isPointInPolygon(47.001, -1.0, square))
        assertFalse(GeoUtils.isPointInPolygon(47.0, -0.999, square))
        assertFalse(GeoUtils.isPointInPolygon(46.0, -1.0, square))
    }

    @Test
    fun testConcavePolygon() {
        assertTrue("bas du L", GeoUtils.isPointInPolygon(47.0005, -0.9985, lShape))
        assertTrue("barre verticale du L", GeoUtils.isPointInPolygon(47.0015, -0.9995, lShape))
        assertFalse("creux du L", GeoUtils.isPointInPolygon(47.0015, -0.9985, lShape))
    }

    @Test
    fun testPolygonOrientationDoesNotMatter() {
        val reversed = square.reversed()
        assertTrue(GeoUtils.isPointInPolygon(47.0, -1.0, reversed))
        assertFalse(GeoUtils.isPointInPolygon(47.001, -1.0, reversed))
    }

    @Test
    fun testDegeneratePolygonIsNeverInside() {
        assertFalse(GeoUtils.isPointInPolygon(47.0, -1.0, emptyList()))
        assertFalse(GeoUtils.isPointInPolygon(47.0, -1.0, square.take(2)))
    }

    @Test
    fun testDistanceToPolygonBoundary() {
        // 0.0005° au nord du bord nord ≈ 55.6 m
        val d = GeoUtils.distanceToPolygonBoundaryMeters(47.001, -1.0, square)
        assertEquals(55.6, d, 1.0)
        // Centre du carré : bord le plus proche = bord est/ouest à 0.0005° de longitude ≈ 38 m
        val inside = GeoUtils.distanceToPolygonBoundaryMeters(47.0, -1.0, square)
        assertEquals(37.9, inside, 1.0)
        assertEquals(0.0, GeoUtils.distanceToPolygonBoundaryMeters(47.0, -1.0, emptyList()), 0.0)
    }

    @Test
    fun testDistanceToPolygonBoundaryNearCorner() {
        // Point au nord-est du coin (47.0005, -0.9995) : distance = distance au coin
        val expected = GeoUtils.calculateDistanceMeters(47.001, -0.999, 47.0005, -0.9995)
        val d = GeoUtils.distanceToPolygonBoundaryMeters(47.001, -0.999, square)
        assertEquals(expected, d, 1.0)
    }

    @Test
    fun testPolygonCenter() {
        val (lat, lon) = GeoUtils.calculatePolygonCenter(square)
        assertEquals(47.0, lat, 1e-9)
        assertEquals(-1.0, lon, 1e-9)
    }

    @Test
    fun testFormatTimeAgo() {
        val now = System.currentTimeMillis()
        assertEquals("Jamais", GeoUtils.formatTimeAgo(null))
        assertEquals("Jamais", GeoUtils.formatTimeAgo(0))
        assertEquals("À l'instant", GeoUtils.formatTimeAgo(now - 2_000))
        assertEquals("Il y a 30s", GeoUtils.formatTimeAgo(now - 30_000))
        assertEquals("Il y a 5 min", GeoUtils.formatTimeAgo(now - 5 * 60_000))
        assertEquals("Il y a 3 h", GeoUtils.formatTimeAgo(now - 3 * 3_600_000))
        assertEquals("Il y a 2 j", GeoUtils.formatTimeAgo(now - 2 * 86_400_000))
    }
}
