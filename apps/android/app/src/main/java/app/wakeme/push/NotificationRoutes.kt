package app.wakeme.push

/**
 * Routes a notification or launcher shortcut may open (IncomingHandler, safety/message
 * notifications, QuackShortcuts).
 * MainActivity is exported, so any app can start it with a "route" extra; only these shapes, with
 * plain ids, are followed (android-intent-security skill: validate all incoming intent extras).
 */
object NotificationRoutes {
    private val ALLOWED = Regex("^(home|friends|focus|(command|safety|conversation|routine|friend)/[A-Za-z0-9-]{0,64})$")

    fun isAllowed(route: String): Boolean = ALLOWED.matches(route)
}
