package app.wakeme.ui.routines

import app.wakeme.designsystem.WMIcon
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import app.wakeme.designsystem.AdaptiveList
import app.wakeme.designsystem.Readable
import app.wakeme.designsystem.fullWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.alarm.LocalRoutines
import app.wakeme.core.InstalledRoutine
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.ui.readiness.ReadinessSection
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

    init {
        viewModelScope.launch {
            local.routines.collect { _s.value = GuestState(it) }
        }
    }

    fun delete(planId: String) = viewModelScope.launch { local.delete(planId) }
}

/**
 * Personal wake-ups without an account (doc 02 §9, doc 03 §5 step 2): make this phone ready,
 * set wake-ups that live only here, and sign in when friends should help. Nothing here needs
 * the network.
 */
@Composable
fun GuestHome(onSignIn: () -> Unit, vm: GuestViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        Readable { RoutineEditorScreen(ownerId = null, onDone = { editing = false }, guest = true) }
        return
    }
    // Outside the main Scaffold: keep the list clear of the system bars and keyboard itself.
    AdaptiveList(Modifier.padding(horizontal = WakeMeTokens.spaceMd), contentPadding = WindowInsets.safeDrawing.asPaddingValues()) {
        fullWidth {
            Gap()
            Text("Your wake-ups", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            Body("They live on this phone only and ring without a connection. Sign in when you want friends to help.")
        }
        items(s.routines, key = { it.first.planId }) { (r, armed) ->
            WMCard {
                Text("${r.localTime} · ${daysText(r.recurrence)}", style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
                if (r.label.isNotBlank()) Body(r.label)
                if (armed) StatusPill("Ready on this phone", Tone.Success) else StatusPill("Not scheduled: allow “Alarms & reminders” below", Tone.Warning)
                QuietButton("Delete this wake-up", onClick = { vm.delete(r.planId) })
            }
        }
        fullWidth { PrimaryButton("New wake-up", onClick = { editing = true }, icon = WMIcon.Plus) }
        fullWidth { WMCard { ReadinessSection() } }
        fullWidth {
            WMCard {
                Text("Wake each other up", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                Body("Sign in to invite friends. Your wake-ups here move to your account and keep ringing on this phone.")
                SecondaryButton("Sign in to add friends", onClick = onSignIn, icon = WMIcon.PersonPlus)
            }
        }
        fullWidth { Gap(WakeMeTokens.spaceXl) }
    }
}
