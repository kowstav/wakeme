package app.wakeme.ui.routines

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.wakeme.alarm.LocalRoutines
import app.wakeme.core.InstalledRoutine
import app.wakeme.designsystem.*
import app.wakeme.ui.Route
import app.wakeme.ui.readiness.ReadyScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuestState(val routines: List<Pair<InstalledRoutine, Boolean>> = emptyList())

@HiltViewModel
class GuestViewModel @Inject constructor(private val local: LocalRoutines) : ViewModel() {
    private val _s = MutableStateFlow(GuestState())
    val state: StateFlow<GuestState> = _s
    init { viewModelScope.launch { local.routines.collect { _s.value = GuestState(it) } } }
    fun delete(planId: String) = viewModelScope.launch { local.delete(planId) }
}

/** Local alarms remain account-free. Each editor has its own navigation-owned ViewModel. */
@Composable
fun GuestHome(onSignIn: () -> Unit, vm: GuestViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val stack = rememberNavBackStack(Route.Routines)
    val close: () -> Unit = { if (stack.size > 1) stack.removeLastOrNull() }
    NavDisplay(
        backStack = stack,
        onBack = close,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider<NavKey> {
            entry<Route.Routines> {
                GuestAlarmContent(s, onNew = { stack.add(Route.RoutineNew()) },
                    onReady = { stack.add(Route.Ready) }, onSignIn = onSignIn, onDelete = { vm.delete(it) })
            }
            entry<Route.RoutineNew> {
                Readable { RoutineEditorScreen(ownerId = null, guest = true, onDone = close) }
            }
            entry<Route.Ready> {
                Readable { ReadyScreen(onContinue = close, continueLabel = "Done", onSkip = close, onBack = close) }
            }
        },
    )
}

/** A quiet, task-first local home; setup and account features are deliberate destinations. */
@Composable
fun GuestAlarmContent(
    state: GuestState,
    onNew: () -> Unit,
    onReady: () -> Unit,
    onSignIn: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleting by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = WM.colors.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PrimaryButton("New wake-up", onClick = onNew, icon = WMIcon.Plus,
                    modifier = Modifier.widthIn(max = ReadableWidth).navigationBarsPadding().padding(horizontal = WakeMeTokens.spaceMd, vertical = WakeMeTokens.spaceSm))
            }
        },
    ) { padding ->
        AdaptiveList(Modifier.padding(padding).consumeWindowInsets(padding).padding(horizontal = WakeMeTokens.spaceMd)) {
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                    Gap(WakeMeTokens.spaceSm)
                    Supporting("ON THIS PHONE")
                    Text("Your wake-ups", style = MaterialTheme.typography.headlineMedium,
                        color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
                }
            }
            if (state.routines.isEmpty()) fullWidth { MorningArtwork(Modifier.fillMaxWidth().height(180.dp)) }
            items(state.routines, key = { it.first.planId }) { (routine, armed) ->
                WMCard(container = WM.colors.heroFill) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                            Text(routine.localTime, style = MaterialTheme.typography.displaySmall, color = WM.colors.onHero)
                            Supporting(daysText(routine.recurrence) + if (routine.label.isBlank()) "" else " · ${routine.label}", color = WM.colors.onHeroSecondary)
                            StatusPill(if (armed) "Scheduled" else "Needs alarm access", if (armed) Tone.Success else Tone.Warning,
                                icon = if (armed) WMIcon.CheckCircle else WMIcon.Warning)
                        }
                        RoundIconButton(WMIcon.Trash, "Delete this wake-up", onClick = { deleting = routine.planId })
                    }
                }
            }
            fullWidth {
                WMCard(onClick = onReady) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        IconChip(WMIcon.Phone, WM.palette.lime)
                        Text("Check permissions", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                        WMIconView(WMIcon.ChevronRight, tint = WM.colors.textSecondary)
                    }
                }
            }
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        ConnectionArtwork(Modifier.size(96.dp))
                        Column {
                            Text("Better together", style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
                            Supporting("Friends \u00b7 Challenges \u00b7 Check-ins")
                        }
                    }
                    SecondaryButton("Sign in", onClick = onSignIn, icon = WMIcon.Friends)
                }
            }
            fullWidth { Gap() }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this wake-up?") },
            text = { Text("It will no longer ring on this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep wake-up") } })
    }
}
