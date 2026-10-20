package app.wakeme.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation 3 destinations of the signed-in app. Keys are saved with the back stack. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Friends : Route
    @Serializable data class Friend(val id: String) : Route
    @Serializable data class Circle(val id: String) : Route
    @Serializable data class Grant(val friendshipId: String) : Route
    @Serializable data class Conversation(val id: String) : Route
    @Serializable data object Routines : Route
    @Serializable data class Routine(val id: String) : Route
    @Serializable data class RoutineNew(val ownerId: String? = null) : Route
    @Serializable data object Challenges : Route
    @Serializable data object ChallengeNew : Route
    @Serializable data class ChallengeEdit(val id: String) : Route
    @Serializable data class Safety(val focusSessionId: String? = null) : Route
    @Serializable data object Focus : Route
    @Serializable data object VoiceSounds : Route
    @Serializable data object You : Route
    @Serializable data object Ready : Route
    @Serializable data object Paywall : Route
}

/** The four top-level destinations; the back stack always starts at Friends. */
val TopLevelRoutes: List<NavKey> = listOf(Route.Friends, Route.Routines, Route.Challenges, Route.You)

/**
 * A route string from a notification, shortcut or the app itself (already checked against
 * [app.wakeme.push.NotificationRoutes]) as a destination. A command opens the Friends tab, where
 * its status is shown. Unknown routes open nothing.
 */
fun routeKey(route: String): Route? {
    val parts = route.split('/', limit = 2)
    val id = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    return when (parts[0]) {
        "home", "friends", "command" -> Route.Friends
        "routines" -> Route.Routines
        "challenges" -> Route.Challenges
        "you" -> Route.You
        "focus" -> Route.Focus
        "routine-new" -> Route.RoutineNew()
        "safety" -> Route.Safety(id)
        "friend" -> id?.let(Route::Friend)
        "conversation" -> id?.let(Route::Conversation)
        "routine" -> id?.let(Route::Routine)
        else -> null
    }
}

/** List-detail groups (the scene key of each list and its panes); null for full-screen destinations. */
fun NavKey.paneGroup(): String? = when (this) {
    Route.Friends, is Route.Friend, is Route.Circle, is Route.Grant, is Route.Conversation -> "friends"
    Route.Routines, is Route.Routine, is Route.RoutineNew -> "routines"
    Route.Challenges, Route.ChallengeNew, is Route.ChallengeEdit -> "challenges"
    else -> null
}

/**
 * Whether a two-pane window shows the top of [backStack] beside its list, the way
 * ListDetailSceneStrategy groups entries: walking back from the top through entries that have a
 * pane, until one without. The navigation area stays visible then (doc 03: a detail is full
 * screen only on phones).
 */
fun showsListBeside(backStack: List<NavKey>): Boolean {
    val group = backStack.lastOrNull()?.paneGroup() ?: return false
    for (i in backStack.indices.reversed()) {
        val key = backStack[i]
        val g = key.paneGroup() ?: return false
        if (g == group && key in TopLevelRoutes) return true
    }
    return false
}
