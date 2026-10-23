package app.wakeme.core

import org.junit.Assert.*
import org.junit.Test

class WakeCompletionTest {
    @Test fun checkInDoesNotClaimTheChallengeWasSolved() {
        assertEquals("acknowledged", WakeCompletion.CHECKED_IN.commandReceipt)
        assertEquals("session_ended", WakeCompletion.CHECKED_IN.occurrenceOutcome)
        assertEquals(listOf(WakeCompletion.SOLVED), WakeCompletion.entries.filter { it.commandReceipt == "challenge_completed" || it.occurrenceOutcome == "challenge_completed" })
        assertEquals("bypassed", WakeCompletion.ESCAPED.commandReceipt)
    }
}
