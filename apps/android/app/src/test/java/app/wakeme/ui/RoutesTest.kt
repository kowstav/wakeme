package app.wakeme.ui

import app.wakeme.push.NotificationRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test
    fun notificationRoutesOpenTheirDestination() {
        assertEquals(Route.Friends, routeKey("home"))
        assertEquals(Route.Friends, routeKey("friends"))
        assertEquals(Route.Friends, routeKey("command/9b2c")) // its status is on the Friends tab
        assertEquals(Route.Focus, routeKey("focus"))
        assertEquals(Route.Friend("f-1"), routeKey("friend/f-1"))
        assertEquals(Route.Conversation("c-1"), routeKey("conversation/c-1"))
        assertEquals(Route.Routine("p-1"), routeKey("routine/p-1"))
        assertEquals(Route.Safety("s-1"), routeKey("safety/s-1"))
        assertEquals(Route.Safety(null), routeKey("safety"))
        assertEquals(Route.RoutineNew(null), routeKey("routine-new"))
    }

    @Test
    fun everyAllowedNotificationRouteHasADestination() {
        listOf("home", "friends", "focus", "command/a1", "safety/a1", "conversation/a1", "routine/a1", "friend/a1").forEach {
            assertTrue(it, NotificationRoutes.isAllowed(it))
            assertTrue(it, routeKey(it) != null)
        }
    }

    @Test
    fun unknownOrIncompleteRoutesOpenNothing() {
        assertNull(routeKey("settings"))
        assertNull(routeKey("friend/"))
        assertNull(routeKey("conversation"))
        assertNull(routeKey(""))
    }

    @Test
    fun aDetailIsBesideItsListOnlyWhenNothingFullScreenIsBetween() {
        assertTrue(showsListBeside(listOf(Route.Friends)))
        assertTrue(showsListBeside(listOf(Route.Friends, Route.Friend("a"))))
        assertTrue(showsListBeside(listOf(Route.Friends, Route.Friend("a"), Route.Grant("a"))))
        assertTrue(showsListBeside(listOf(Route.Friends, Route.Routines, Route.Routine("p"))))
        // A suggested routine opened from a friend has no Routines list beside it.
        assertFalse(showsListBeside(listOf(Route.Friends, Route.Friend("a"), Route.RoutineNew("u"))))
        // Full-screen destinations end the group.
        assertFalse(showsListBeside(listOf(Route.Friends, Route.Safety(), Route.Friend("a"))))
        assertFalse(showsListBeside(listOf(Route.Friends, Route.You, Route.Paywall)))
        // A notification straight to a friend, with Friends at the bottom of the stack.
        assertTrue(showsListBeside(listOf(Route.Friends, Route.Conversation("c"))))
    }
}
