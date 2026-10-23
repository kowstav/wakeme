package app.wakeme.ui.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Joining accepts a code in any shape, or the whole link (Postel's law). */
class InviteInputTest {
    @Test fun aCodeIsReadInAnyCaseWithOrWithoutSeparators() {
        val expected = InviteInput(null, "S9P-KR4-EZG")
        assertEquals(expected, InviteInput.parse("S9P-KR4-EZG"))
        assertEquals(expected, InviteInput.parse("s9p kr4 ezg"))
        assertEquals(expected, InviteInput.parse(" s9pkr4ezg\n"))
        assertEquals(expected, InviteInput.parse("S9P–KR4–EZG"))
    }

    @Test fun aPastedLinkUsesItsToken() {
        assertEquals(InviteInput("-M1D7fPJD53Bhnk_MGLL3QpkodTvd87u", null), InviteInput.parse("https://wakeme.app/i/-M1D7fPJD53Bhnk_MGLL3QpkodTvd87u?ref=share"))
        assertEquals(InviteInput("abc", null), InviteInput.parse("Join me: http://127.0.0.1:8787/i/abc (code S9P-KR4-EZG)"))
    }

    @Test fun anythingElseIsRefused() {
        assertNull(InviteInput.parse(""))
        assertNull(InviteInput.parse("S9P-KR4"))
        assertNull(InviteInput.parse("S9P-KR4-EZGX"))
        assertNull(InviteInput.parse("https://wakeme.app/i/"))
    }
}
