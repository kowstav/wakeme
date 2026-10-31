package app.wakeme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the Swift FocusTests. */
class FocusTest {
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L

    @Test
    fun aSessionIsBoundedByTheFriendsLimitAndFourHours() {
        assertEquals(t0 + 30 * min, startFocus("a", t0, 45, FocusLimits(30, 2, emptyList()))!!.endsAt)
        assertEquals(t0 + 240 * min, startFocus("b", t0, 600)!!.endsAt)
        assertNull(startFocus("c", t0, 0))
    }

    @Test
    fun theShieldAppliesOnlyWhileActive() {
        val s = startFocus("a", t0, 25)!!
        assertTrue(shouldShield(s, t0 + min))
        assertFalse("ends by itself, offline", shouldShield(s, t0 + 25 * min))
        assertEquals(FocusState.Finished("completed"), focusState(s, t0 + 26 * min))
        assertFalse(shouldShield(null, t0))
    }

    @Test
    fun aChallengeEarnsAShortBreakAtMostThreeTimes() {
        var s = startFocus("a", t0, 60)!!
        val onBreak = takeBreak(s, t0 + 10 * min)!!
        assertFalse(shouldShield(onBreak, t0 + 12 * min))
        assertTrue("the break ends by itself", shouldShield(onBreak, t0 + 16 * min))
        s = onBreak
        repeat(2) { i -> s = takeBreak(s, t0 + (20 + i * 10) * min)!! }
        assertNull("no fourth break", takeBreak(s, t0 + 50 * min))
    }

    @Test
    fun aBreakNeverOutlastsTheSession() {
        val s = startFocus("a", t0, 10)!!
        assertEquals(s.endsAt, takeBreak(s, t0 + 8 * min)!!.breakUntil)
    }

    @Test
    fun endFocusIsAlwaysAvailableAndImmediate() {
        val s = startFocus("a", t0, 25)!!
        val ended = endFocus(s, t0 + 3 * min)
        assertEquals(FocusState.Finished("ended_early"), focusState(ended, t0 + 3 * min))
        assertFalse(shouldShield(ended, t0 + 4 * min))
        assertEquals(ended, endFocus(ended, t0 + 5 * min))
        assertEquals("completed", endFocus(s, t0 + 30 * min).endReason)
    }
}
