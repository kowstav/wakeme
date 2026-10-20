package app.wakeme.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRoutesTest {
    @Test
    fun routesOurNotificationsCreateAreFollowed() {
        for (r in listOf("home", "friends", "command/4f1c2a9e-0b7d-4c1e-9a55-1d2e3f4a5b6c", "safety/", "conversation/abc-123", "routine/r1", "focus", "friend/fs-1")) {
            assertTrue(r, NotificationRoutes.isAllowed(r))
        }
    }

    @Test
    fun anythingElseFromAnotherAppIsIgnored() {
        for (r in listOf("grant/x", "challenge-edit/1", "routine-new", "paywall", "command/../grant", "conversation/a b", "safety/x?y=1", "")) {
            assertFalse(r, NotificationRoutes.isAllowed(r))
        }
    }
}
