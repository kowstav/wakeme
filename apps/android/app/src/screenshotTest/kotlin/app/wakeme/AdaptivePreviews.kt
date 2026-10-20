package app.wakeme

import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import app.wakeme.core.Capability
import app.wakeme.core.GrantLimits
import app.wakeme.core.Recurrence
import app.wakeme.data.net.ChallengeDto
import app.wakeme.data.net.CircleDto
import app.wakeme.data.net.FriendAction
import app.wakeme.data.net.FriendDto
import app.wakeme.data.net.GrantDto
import app.wakeme.data.net.PlanDto
import app.wakeme.data.net.PlanVersionDto
import app.wakeme.data.net.PublicUser
import app.wakeme.data.net.TemplateDto
import app.wakeme.designsystem.Readable
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.ui.challenges.ChallengesList
import app.wakeme.ui.challenges.ChallengesState
import app.wakeme.ui.friends.CirclesList
import app.wakeme.ui.friends.FriendsList
import app.wakeme.ui.friends.FriendsState
import app.wakeme.ui.onboarding.WelcomeScreen
import app.wakeme.ui.routines.RoutinesList
import app.wakeme.ui.routines.RoutinesState
import com.android.tools.screenshot.PreviewTest
import kotlinx.serialization.json.JsonObject

/**
 * android/skills adaptive, step 1: the main screens on every major form factor. Phones get a
 * bottom bar and one column of cards; foldables, tablets and desktop windows get a navigation
 * rail and more columns. Rendered by layoutlib (not a device); reference images are recorded in
 * CI for review before they are committed.
 */
@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
@Preview(name = "Desktop", device = Devices.DESKTOP, showBackground = true)
annotation class FormFactorPreviews

private const val NOW = "2026-10-20T08:00:00Z"
private val me = PublicUser("me", "Sam")

private fun grant(vararg capabilities: String) = GrantDto(
    id = "g-" + capabilities.joinToString(""), grantorId = "them", granteeId = "me", version = 1,
    capabilities = capabilities.toList(), limits = GrantLimits(), validFrom = NOW, validUntil = "2027-01-20T08:00:00Z",
    paused = false, updatedAt = NOW,
)

private fun friend(id: String, name: String, grant: GrantDto?, quackNow: Boolean, reason: String? = null) = FriendDto(
    friendshipId = id, user = PublicUser("u-$id", name), state = "active", since = NOW, theyAllowMe = grant,
    actions = if (grant == null) emptyList() else listOf(FriendAction(Capability.NUDGE, granted = true, availableNow = quackNow, reason = reason)),
)

private val friends = listOf(
    friend("1", "Alex", grant(Capability.NUDGE, Capability.ALARM_REQUEST), quackNow = true),
    friend("2", "Maya", grant(Capability.NUDGE, Capability.FOCUS), quackNow = false, reason = "outside_window"),
    friend("3", "Priya", grant(Capability.NUDGE), quackNow = true),
    friend("4", "Jordan", null, quackNow = false),
)

private val circles = listOf(
    CircleDto("c1", "Flat 4B", role = "owner", memberCount = 4, conversationId = "k1", createdAt = NOW),
    CircleDto("c2", "Exam study group", role = "member", memberCount = 6, conversationId = "k2", createdAt = NOW),
)

private fun plan(id: String, time: String, label: String, owner: PublicUser = me, creator: PublicUser = me) = PlanDto(
    id = id, owner = owner, creator = creator, state = "active", readiness = "ready", createdAt = NOW, updatedAt = NOW,
    accepted = PlanVersionDto(
        version = 1, label = label, localTime = time, timeZone = "Europe/London", timeZonePolicy = "fixed",
        recurrence = Recurrence.weekly(listOf(1, 2, 3, 4, 5)), soundId = "bundled:rise.gentle", ringSeconds = 60,
        createdBy = creator.id, createdAt = NOW,
    ),
)

private val tabs = listOf("Friends" to Icons.Outlined.People, "Routines" to Icons.Outlined.Alarm, "Challenges" to Icons.Outlined.EmojiEvents, "You" to Icons.Outlined.AccountCircle)

/** The app's adaptive shell: bottom bar on phones, rail on wider windows (same as MainTabs). */
@Composable
private fun Shell(selected: Int, content: @Composable () -> Unit) {
    WakeMeTheme {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                tabs.forEachIndexed { i, (label, icon) ->
                    item(selected = i == selected, onClick = {}, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                }
            },
        ) { content() }
    }
}

@PreviewTest
@FormFactorPreviews
@Preview(name = "Phone dark", device = Devices.PHONE, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FriendsTab() = Shell(0) {
    FriendsList(
        s = FriendsState(loading = false, friends = friends),
        notificationsOn = true,
        onOpen = {}, onFixReadiness = {}, onTurnOnNotifications = {}, onInvite = {}, onRedeem = {},
        circles = { CirclesList(circles, onOpen = {}, onNew = {}) },
    )
}

@PreviewTest
@FormFactorPreviews
@Composable
fun RoutinesTab() = Shell(1) {
    RoutinesList(
        s = RoutinesState(
            mine = listOf(plan("p1", "07:00", "Work days"), plan("p2", "06:15", "Gym", creator = PublicUser("u-1", "Alex")), plan("p3", "08:30", "")),
            helping = listOf(plan("p4", "06:45", "Early shift", owner = PublicUser("u-2", "Maya"), creator = me)),
            myId = me.id,
        ),
        onNew = {}, onOpen = {}, onFixReadiness = {},
    )
}

@PreviewTest
@FormFactorPreviews
@Composable
fun ChallengesTab() = Shell(2) {
    ChallengesList(
        s = ChallengesState(
            templates = listOf(
                TemplateDto("t1", "Wake-up maths", "Two quick sums before the alarm can be dismissed.", JsonObject(emptyMap())),
                TemplateDto("t2", "Flat trivia", "Questions about your own group's jokes, checked on the phone.", JsonObject(emptyMap())),
                TemplateDto("t3", "Type the phrase", "Type a short phrase exactly; there is always a way out.", JsonObject(emptyMap())),
            ),
            challenges = listOf(ChallengeDto("x1", creatorId = me.id, title = "Who left the milk out?", latestRevision = 2, publishedRevision = 2, revisions = emptyList())),
        ),
        onNew = {}, onTry = {}, onOpen = {}, canEdit = { true }, onEdit = {},
    )
}

@PreviewTest
@FormFactorPreviews
@Composable
fun Welcome() = WakeMeTheme { Readable { WelcomeScreen(onInvite = {}, onSelf = {}) } }
