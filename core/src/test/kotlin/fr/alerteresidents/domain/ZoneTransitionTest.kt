package fr.alerteresidents.domain

import fr.alerteresidents.data.model.AlertState
import fr.alerteresidents.data.model.Resident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneTransitionTest {
    private val inside = ZoneEvaluation(true, 10.0, 0.0, false)
    private val ambiguous = ZoneEvaluation(false, 160.0, 10.0, false)
    private val clearlyOut = ZoneEvaluation(false, 500.0, 350.0, true)
    private val r = Resident(id = 1, name = "A")
    private val reminder = 5 * 60_000L

    @Test
    fun `stays inside`() {
        assertEquals(TransitionEvent.NONE, ZoneTransition.apply(r, inside, "f1", 0, reminder, false).event)
    }

    @Test
    fun `clear exit is confirmed immediately`() {
        val t = ZoneTransition.apply(r, clearlyOut, "f1", 1000, reminder, false)
        assertEquals(TransitionEvent.EXIT_CONFIRMED, t.event)
        assertFalse(t.resident.isInZone)
        assertEquals(AlertState.ACTIVE, t.resident.alertState)
        assertEquals(1000L, t.resident.exitedAt)
    }

    @Test
    fun `ambiguous exit needs a second fix or a delay`() {
        val first = ZoneTransition.apply(r, ambiguous, "f1", 0, reminder, false)
        assertEquals(TransitionEvent.EXIT_PENDING, first.event)
        assertTrue(first.resident.isInZone)
        assertEquals(TransitionEvent.EXIT_PENDING, ZoneTransition.apply(first.resident, ambiguous, "f1", 30_000, reminder, false).event)
        assertEquals(TransitionEvent.EXIT_CONFIRMED, ZoneTransition.apply(first.resident, ambiguous, "f2", 30_000, reminder, false).event)
        assertEquals(TransitionEvent.EXIT_CONFIRMED, ZoneTransition.apply(first.resident, ambiguous, "f1", 60_000, reminder, false).event)
    }

    @Test
    fun `return clears the alert state`() {
        val out = r.copy(isInZone = false, alertState = AlertState.HANDLING, alertHandledBy = "X", exitedAt = 5)
        val t = ZoneTransition.apply(out, inside, "f9", 100, reminder, false)
        assertEquals(TransitionEvent.RETURNED, t.event)
        assertTrue(t.resident.isInZone)
        assertEquals(AlertState.NONE, t.resident.alertState)
    }

    @Test
    fun `reminder only when active, not ringing and delay elapsed`() {
        val out = r.copy(isInZone = false, alertState = AlertState.ACTIVE, lastAlarmAt = 0)
        assertEquals(TransitionEvent.NONE, ZoneTransition.apply(out, clearlyOut, "f", reminder - 1, reminder, false).event)
        assertEquals(TransitionEvent.REMINDER, ZoneTransition.apply(out, clearlyOut, "f", reminder, reminder, false).event)
        assertEquals(TransitionEvent.NONE, ZoneTransition.apply(out, clearlyOut, "f", reminder, reminder, true).event)
        assertEquals(TransitionEvent.NONE, ZoneTransition.apply(out.copy(alertState = AlertState.HANDLING), clearlyOut, "f", reminder * 3, reminder, false).event)
    }
}
