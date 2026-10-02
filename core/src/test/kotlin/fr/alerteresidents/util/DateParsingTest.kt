package fr.alerteresidents.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateParsingTest {
    private val expected = 1_768_465_800_000L // 2026-01-15T08:30:00Z

    @Test
    fun `parses Weenect formats`() {
        assertEquals(expected, DateParsing.parseIso("2026-01-15T08:30:00+00:00"))
        assertEquals(expected, DateParsing.parseIso("2026-01-15T09:30:00+01:00"))
        assertEquals(expected, DateParsing.parseIso("2026-01-15T08:30:00Z"))
        assertEquals(expected, DateParsing.parseIso("2026-01-15T08:30:00.123456+00:00"))
        assertEquals(expected, DateParsing.parseIso("2026-01-15T08:30:00"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(DateParsing.parseIso(null))
        assertNull(DateParsing.parseIso(""))
        assertNull(DateParsing.parseIso("hier"))
    }

    @Test
    fun `format then parse round trips`() {
        assertEquals(expected, DateParsing.parseIso(DateParsing.formatIso(expected)))
    }
}
