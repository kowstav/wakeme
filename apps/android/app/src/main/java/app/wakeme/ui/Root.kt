package app.wakeme.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.wakeme.R
import app.wakeme.ui.challenges.ChallengeEditorScreen
import app.wakeme.ui.challenges.ChallengesScreen
import app.wakeme.ui.friends.FriendDetailScreen
import app.wakeme.ui.friends.FriendsScreen
import app.wakeme.ui.grants.GrantEditorScreen
import app.wakeme.ui.messages.ConversationScreen
import app.wakeme.ui.onboarding.InviteScreen
import app.wakeme.ui.onboarding.ProfileScreen
import app.wakeme.ui.onboarding.SignInScreen
import app.wakeme.ui.onboarding.WelcomeScreen
import app.wakeme.ui.paywall.PaywallScreen
import app.wakeme.ui.routines.RoutineEditorScreen
import app.wakeme.ui.routines.RoutineDetailScreen
import app.wakeme.ui.routines.RoutinesScreen
import app.wakeme.ui.safety.SafetyScreen
import app.wakeme.ui.you.YouScreen

@Composable
fun WakeMeRoot(app: AppViewModel) {
    val gate by app.gate.collectAsState()
    val error by app.error.collectAsState()
    when (val g = gate) {
        Gate.Loading -> androidx.compose.foundation.layout.Box(Modifier) { CircularProgressIndicator() }
        Gate.Welcome -> WelcomeScreen(onInvite = app::chooseInviteFlow, onSelf = app::chooseSelfSetup)
        Gate.SignIn -> SignInScreen(app)
        Gate.Profile -> ProfileScreen(onCreate = app::createProfile)
        is Gate.Invite -> InviteScreen(g.preview, onAccept = { app.acceptInvite(g.token) }, onDecline = app::declineInvite)
        Gate.Main -> MainTabs(app)
    }
    error?.let {
        AlertDialog(
            onDismissRequest = app::clearError,
            confirmButton = { TextButton(onClick = app::clearError) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("friends", R.string.tab_friends, Icons.Outlined.People),
    Tab("routines", R.string.tab_routines, Icons.Outlined.Alarm),
    Tab("challenges", R.string.tab_challenges, Icons.Outlined.EmojiEvents),
    Tab("you", R.string.tab_you, Icons.Outlined.AccountCircle),
)

@Composable
private fun MainTabs(app: AppViewModel) {
    val nav = rememberNavController()
    val pending by app.route.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { r ->
            runCatching { nav.navigate(r) }
            app.consumeRoute()
        }
    }
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        bottomBar = {
            if (current in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("friends"); launchSingleTop = true } },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavGraph(nav, app, Modifier.padding(padding))
    }
}

@Composable
private fun NavGraph(nav: NavHostController, app: AppViewModel, modifier: Modifier) {
    NavHost(nav, startDestination = "friends", modifier = modifier) {
        composable("friends") { FriendsScreen(onOpen = { nav.navigate("friend/$it") }) }
        composable("friend/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            FriendDetailScreen(
                friendshipId = it.arguments!!.getString("id")!!,
                onEditPermissions = { fid -> nav.navigate("grant/$fid") },
                onMessages = { cid -> nav.navigate("conversation/$cid") },
                onProposeRoutine = { ownerId -> nav.navigate("routine-new?owner=$ownerId") },
                onSafety = { nav.navigate("safety") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("grant/{id}", listOf(navArgument("id") { type = NavType.StringType })) { GrantEditorScreen(friendshipId = it.arguments!!.getString("id")!!, onDone = { nav.popBackStack() }) }
        composable("conversation/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ConversationScreen(conversationId = it.arguments!!.getString("id")!!) }
        composable("command/{id}") { FriendsScreen(onOpen = { id -> nav.navigate("friend/$id") }) }
        composable("routines") { RoutinesScreen(onNew = { nav.navigate("routine-new") }, onOpen = { nav.navigate("routine/$it") }) }
        composable("routine/{id}", listOf(navArgument("id") { type = NavType.StringType })) { RoutineDetailScreen(planId = it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        composable("routine-new?owner={owner}", listOf(navArgument("owner") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            RoutineEditorScreen(ownerId = it.arguments?.getString("owner"), onDone = { nav.popBackStack() })
        }
        composable("challenges") { ChallengesScreen(onNew = { nav.navigate("challenge-new") }, onUpgrade = { nav.navigate("paywall") }) }
        composable("challenge-new") { ChallengeEditorScreen(onDone = { nav.popBackStack() }, onUpgrade = { nav.navigate("paywall") }) }
        composable("safety") { SafetyScreen() }
        composable("safety/{id}") { SafetyScreen() }
        composable("you") { YouScreen(app = app, onPaywall = { nav.navigate("paywall") }, onSafety = { nav.navigate("safety") }) }
        composable("paywall") { PaywallScreen(onClose = { nav.popBackStack() }) }
    }
}
