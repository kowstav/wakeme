package app.wakeme.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.wakeme.R
import app.wakeme.designsystem.DetailPlaceholder
import app.wakeme.designsystem.DuckMark
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMMotion
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.designsystem.Readable
import app.wakeme.focus.FocusScreen
import app.wakeme.media.VoiceSoundsScreen
import app.wakeme.ui.challenges.ChallengeEditorScreen
import app.wakeme.ui.challenges.ChallengesScreen
import app.wakeme.ui.friends.CircleScreen
import app.wakeme.ui.friends.FriendDetailScreen
import app.wakeme.ui.friends.FriendsScreen
import app.wakeme.ui.grants.GrantEditorScreen
import app.wakeme.ui.messages.ConversationScreen
import app.wakeme.ui.onboarding.InviteScreen
import app.wakeme.ui.onboarding.ProfileScreen
import app.wakeme.config.AppConfig
import app.wakeme.ui.onboarding.GoogleSignInButton
import app.wakeme.ui.onboarding.SignInScreen
import app.wakeme.ui.onboarding.WelcomeScreen
import app.wakeme.ui.paywall.PaywallScreen
import app.wakeme.ui.readiness.ReadyScreen
import app.wakeme.ui.routines.GuestHome
import app.wakeme.ui.routines.RoutineDetailScreen
import app.wakeme.ui.routines.RoutineEditorScreen
import app.wakeme.ui.routines.RoutinesScreen
import app.wakeme.ui.routines.WakeThanksScreen
import app.wakeme.ui.safety.SafetyScreen
import app.wakeme.ui.you.YouScreen

@Composable
fun WakeMeRoot(app: AppViewModel) {
    val gate by app.gate.collectAsState()
    val error by app.error.collectAsState()
    // Setup steps fade through one another (no sliding: they aren't a spatial stack).
    AnimatedContent(targetState = gate, contentKey = { it::class }, transitionSpec = { WMMotion.fadeThrough() }, label = "gate") { g ->
    when (g) {
        Gate.Loading -> Box(Modifier.fillMaxSize().background(WM.colors.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
                DuckMark(size = 64.dp)
                CircularProgressIndicator(Modifier.size(24.dp), color = WM.colors.accentText, strokeWidth = 2.5.dp)
            }
        }
        Gate.Welcome -> Readable { WelcomeScreen(onInvite = app::chooseInviteFlow, onSelf = app::chooseSelfSetup,
            signInAction = if (AppConfig.googleServerClientId.isNotBlank()) { { GoogleSignInButton(app) } } else null) }
        Gate.SignIn -> Readable { SignInScreen(app) }
        Gate.Profile -> Readable { ProfileScreen(onCreate = app::createProfile) }
        is Gate.Invite -> Readable { InviteScreen(g.preview, onAccept = { app.acceptInvite(g.token) }, onDecline = app::declineInvite) }
        Gate.Ready -> Readable { ReadyScreen(onContinue = { app.finishSelfSetup(openRoutineEditor = true) }, onSkip = { app.finishSelfSetup(openRoutineEditor = false) }) }
        Gate.Main -> MainTabs(app)
        Gate.Guest -> GuestHome(onSignIn = app::signInFromGuest)
    }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = app::clearError,
            confirmButton = { TextButton(onClick = app::clearError) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

/** Bloom icons with their words: tab bars keep visible labels (Material and Apple guidance), and the selected tab sits in the lime indicator. */
private data class Tab(val route: Route, val label: Int, val icon: WMIcon)

private val tabs = listOf(
    Tab(Route.Friends, R.string.tab_friends, WMIcon.Friends),
    Tab(Route.Routines, R.string.tab_routines, WMIcon.Alarm),
    Tab(Route.Challenges, R.string.tab_challenges, WMIcon.Trophy),
    Tab(Route.You, R.string.tab_you, WMIcon.You),
)

/** Tabs keep Friends at the bottom of the stack, so Back from another tab returns there. */
private fun NavBackStack<NavKey>.selectTab(tab: Route) {
    val target: List<NavKey> = if (tab == Route.Friends) listOf(Route.Friends) else listOf(Route.Friends, tab)
    if (toList() == target) return
    addAll(target)
    repeat(size - target.size) { removeAt(0) }
}

/** Opens [key] from [list], replacing whatever detail was beside the list (two panes) or above it. */
private fun NavBackStack<NavKey>.openFrom(list: Route, key: Route) {
    val i = lastIndexOf(list)
    if (i >= 0) while (size > i + 1) removeAt(lastIndex)
    add(key)
}

/** Closes [key] and anything opened from it. */
private fun NavBackStack<NavKey>.close(key: Route) {
    val i = lastIndexOf(key)
    if (i > 0) while (size > i) removeAt(lastIndex)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MainTabs(app: AppViewModel) {
    val backStack = rememberNavBackStack(Route.Friends)
    val pending by app.route.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { r ->
            // A restored launch Intent or another tap on the same notification can offer the
            // current destination again. Keep its saved state/ViewModel instead of adding it twice.
            routeKey(r)?.let { key ->
                if (key in TopLevelRoutes) backStack.selectTab(key)
                else if (backStack.lastOrNull() != key) backStack.add(key)
            }
            app.consumeRoute()
        }
    }
    // android/skills adaptive, step 3: list-detail scenes. Two panes on tablets, unfolded foldables
    // and desktop windows (the list stays beside its detail); one pane at a time on phones.
    // No spacer between panes (Nav3 recipe, b/418201867): each pane already has its own margins.
    val windowInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowInfo) { calculatePaneScaffoldDirective(windowInfo).copy(horizontalPartitionSpacerSize = 0.dp) }
    val listDetail = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    val top = backStack.last()
    val currentTab = backStack.lastOrNull { it in TopLevelRoutes } ?: Route.Friends
    // Step 2: a bottom bar on phones, a navigation rail on larger windows. Hidden on full-screen
    // destinations; a detail shown beside its list keeps it.
    val isNavBarVisible = top in TopLevelRoutes || (directive.maxHorizontalPartitions > 1 && showsListBeside(backStack))
    val navState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(isNavBarVisible) { if (isNavBarVisible) navState.show() else navState.hide() }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEach { t ->
                item(
                    selected = currentTab == t.route,
                    onClick = { backStack.selectTab(t.route) },
                    icon = { Icon(painterResource(t.icon.res), contentDescription = null) },
                    label = { Text(stringResource(t.label)) },
                )
            }
        },
        state = navState,
    ) {
        // The adaptive scaffold doesn't pass insets down: keep content clear of the status bar,
        // cutouts and side bars here; screens add the bottom (navigation bar, keyboard) themselves.
        val shellInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        // Navigation 3 defaults to a 700 ms slide; Wake Me uses a short shared-axis move instead:
        // the new screen arrives from the reading direction by a tenth of the width while fading
        // in (emphasized, 320 ms) and the old one fades out (180 ms). Back and predictive back
        // reverse it. With Remove animations on, screens simply change.
        val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        val forward: () -> ContentTransform = {
            (slideInHorizontally(WMMotion.enter()) { towardEnd * it / 10 } + fadeIn(WMMotion.enter())) togetherWith fadeOut(WMMotion.exit())
        }
        val backward: () -> ContentTransform = {
            fadeIn(WMMotion.enter()) togetherWith (slideOutHorizontally(WMMotion.exit()) { towardEnd * it / 10 } + fadeOut(WMMotion.exit()))
        }
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(shellInsets).consumeWindowInsets(shellInsets),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            // Each destination keeps its own saved UI state and its own ViewModels, cleared when it's popped.
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)),
            ),
            sceneStrategies = listOf(listDetail),
            transitionSpec = { forward() },
            popTransitionSpec = { backward() },
            predictivePopTransitionSpec = { backward() },
            entryProvider = entries(backStack, app),
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun entries(backStack: NavBackStack<NavKey>, app: AppViewModel) = entryProvider<NavKey> {
    val friends = Route.Friends.paneGroup()!!
    val routines = Route.Routines.paneGroup()!!
    val challenges = Route.Challenges.paneGroup()!!

    // ---- Friends: the list, a friend or circle beside it, permissions and messages as a third pane.
    entry<Route.Friends>(metadata = ListDetailSceneStrategy.listPane(friends) { DetailPlaceholder("Choose a friend to quack them, or to change what they can do on your phone.") }) {
        FriendsScreen(
            onOpen = { id -> backStack.openFrom(Route.Friends, Route.Friend(id)) },
            onOpenCircle = { id -> backStack.openFrom(Route.Friends, Route.Circle(id)) },
            onTop = backStack.last() == Route.Friends,
        )
    }
    entry<Route.Friend>(metadata = ListDetailSceneStrategy.detailPane(friends)) { key ->
        Readable {
            FriendDetailScreen(
                friendshipId = key.id,
                onEditPermissions = { fid -> backStack.add(Route.Grant(fid)) },
                onMessages = { cid -> backStack.add(Route.Conversation(cid)) },
                onProposeRoutine = { ownerId -> backStack.add(Route.RoutineNew(ownerId)) },
                onSafety = { backStack.add(Route.Safety()) },
                onBack = { backStack.close(key) },
            )
        }
    }
    entry<Route.Circle>(metadata = ListDetailSceneStrategy.detailPane(friends)) { key ->
        Readable { CircleScreen(circleId = key.id, onMessages = { c -> backStack.add(Route.Conversation(c)) }, onBack = { backStack.close(key) }) }
    }
    entry<Route.Grant>(metadata = ListDetailSceneStrategy.extraPane(friends)) { key ->
        Readable { GrantEditorScreen(friendshipId = key.friendshipId, onDone = { backStack.close(key) }) }
    }
    entry<Route.Conversation>(metadata = ListDetailSceneStrategy.extraPane(friends)) { key ->
        Readable { ConversationScreen(conversationId = key.id, onBack = { backStack.close(key) }) }
    }

    // ---- Routines
    entry<Route.Routines>(metadata = ListDetailSceneStrategy.listPane(routines) { DetailPlaceholder("Choose a wake-up to see when it rings and whether this phone is ready.") }) {
        RoutinesScreen(
            onNew = { backStack.openFrom(Route.Routines, Route.RoutineNew()) },
            onOpen = { id -> backStack.openFrom(Route.Routines, Route.Routine(id)) },
            onFixReadiness = { backStack.add(Route.Ready) },
            onTop = backStack.last() == Route.Routines,
        )
    }
    entry<Route.Routine>(metadata = ListDetailSceneStrategy.detailPane(routines)) { key ->
        Readable { RoutineDetailScreen(planId = key.id, onBack = { backStack.close(key) }, onReuse = { backStack.openFrom(Route.Routines, Route.RoutineNew(reuseId = key.id)) }) }
    }
    entry<Route.RoutineNew>(metadata = ListDetailSceneStrategy.detailPane(routines)) { key ->
        Readable { RoutineEditorScreen(ownerId = key.ownerId, onDone = { backStack.close(key) }, reuseId = key.reuseId) }
    }

    // ---- Challenges
    entry<Route.Challenges>(metadata = ListDetailSceneStrategy.listPane(challenges) { DetailPlaceholder("Create a challenge, or choose one of yours to edit.") }) {
        ChallengesScreen(
            onNew = { backStack.openFrom(Route.Challenges, Route.ChallengeNew) },
            onEdit = { id -> backStack.openFrom(Route.Challenges, Route.ChallengeEdit(id)) },
            onUpgrade = { backStack.add(Route.Paywall) },
            onTop = backStack.last() == Route.Challenges,
        )
    }
    entry<Route.ChallengeNew>(metadata = ListDetailSceneStrategy.detailPane(challenges)) {
        Readable { ChallengeEditorScreen(onDone = { backStack.close(Route.ChallengeNew) }, onUpgrade = { backStack.add(Route.Paywall) }) }
    }
    entry<Route.ChallengeEdit>(metadata = ListDetailSceneStrategy.detailPane(challenges)) { key ->
        Readable { ChallengeEditorScreen(onDone = { backStack.close(key) }, onUpgrade = { backStack.add(Route.Paywall) }, editId = key.id) }
    }

    // ---- Full-screen destinations
    entry<Route.Safety> { key -> Readable { SafetyScreen(focusSessionId = key.focusSessionId, onBack = { backStack.close(key) }) } }
    entry<Route.Focus> { Readable { FocusScreen(onBack = { backStack.close(Route.Focus) }) } }
    entry<Route.VoiceSounds> { Readable { VoiceSoundsScreen(onBack = { backStack.close(Route.VoiceSounds) }) } }
    entry<Route.WakeThanks> { key -> Readable { WakeThanksScreen(key.kind, key.id, onDone = { backStack.close(key) }) } }
    entry<Route.You> {
        Readable {
            YouScreen(
                app = app,
                onPaywall = { backStack.add(Route.Paywall) },
                onSafety = { backStack.add(Route.Safety()) },
                onFocus = { backStack.add(Route.Focus) },
                onVoiceSounds = { backStack.add(Route.VoiceSounds) },
            )
        }
    }
    entry<Route.Ready> { Readable { ReadyScreen(onContinue = { backStack.close(Route.Ready) }, continueLabel = "Done", onSkip = { backStack.close(Route.Ready) }, onBack = { backStack.close(Route.Ready) }) } }
    entry<Route.Paywall> { Readable { PaywallScreen(onClose = { backStack.close(Route.Paywall) }) } }
}
