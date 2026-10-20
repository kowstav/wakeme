package app.wakeme.focus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import app.wakeme.R
import app.wakeme.core.FocusLimits
import app.wakeme.core.FocusState
import app.wakeme.core.GrantSnapshot
import app.wakeme.core.focusState
import app.wakeme.core.startFocus
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.Prefs
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.receipts.Receipt
import app.wakeme.receipts.ReceiptQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LaunchableApp(val packageName: String, val label: String)

@HiltViewModel
class FocusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: FocusStore,
    private val db: LocalDb,
    private val prefs: Prefs,
    private val receipts: ReceiptQueue,
) : ViewModel() {
    /** Launcher apps, listed through a <queries> launcher intent (no QUERY_ALL_PACKAGES). */
    val apps: List<LaunchableApp> by lazy {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** False in builds where the service is switched off (release, until Play approves the declaration). */
    val available: Boolean get() = context.resources.getBoolean(R.bool.focus_service_available)

    fun serviceOn(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, FocusShieldService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun toggle(pkg: String) {
        store.chosenApps = store.chosenApps.let { if (pkg in it) it - pkg else it + pkg }
    }

    fun startSelf(minutes: Int) {
        startFocus(UUID.randomUUID().toString(), System.currentTimeMillis(), minutes)?.let(store::start)
    }

    /** A friend's request: the recipient chose to start it, inside the limit they gave that friend. */
    fun acceptRequest() = viewModelScope.launch {
        val r = store.request.value ?: return@launch
        val me = prefs.currentMe()
        val limits: FocusLimits? = me?.let { db.grants().live(grantorId = it.id, granteeId = r.friendId) }
            ?.let { runCatching { WakeMeApiJson.decodeFromString(GrantSnapshot.serializer(), it.json) }.getOrNull() }?.limits?.focus
        startFocus(UUID.randomUUID().toString(), System.currentTimeMillis(), r.minutes, limits, r.friendId, r.friendName, r.commandId)?.let(store::start)
        receipts.enqueue(Receipt.Command(r.commandId, "acknowledged"))
        store.offer(null)
    }

    fun declineRequest() = store.offer(null)
}

/**
 * Focus (doc 03 §4E, ADR 0014): choose apps to keep closed, start a finite session yourself or
 * when a friend asks, and end it whenever you want. The chosen apps never leave this phone.
 */
@Composable
fun FocusScreen(vm: FocusViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val session by vm.store.session.collectAsState()
    val request by vm.store.request.collectAsState()
    var chosen by remember { mutableStateOf(vm.store.chosenApps) }
    var serviceOn by remember { mutableStateOf(vm.serviceOn()) }
    var disclosing by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(25) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    LifecycleResumeEffect(Unit) {
        vm.store.reload()
        serviceOn = vm.serviceOn()
        onPauseOrDispose { }
    }
    val state = session?.let { focusState(it, now) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text("Focus", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Body("Choose apps to keep closed during a focus session. Your choice stays on this phone; friends never see which apps.")
        if (!vm.available) {
            StatusPill("Focus isn't available in this build yet.", Tone.Neutral)
            return@Column
        }
        request?.let { r ->
            WMCard {
                Text("${r.friendName} asks you to focus for ${r.minutes} min", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                Body("You choose. Nothing changes unless you start it, and you can end it anytime.")
                PrimaryButton("Start focus", onClick = { vm.acceptRequest() }, enabled = serviceOn && chosen.isNotEmpty())
                QuietButton("Not now", onClick = { vm.declineRequest() })
            }
        }
        val s = session
        if (s != null && (state is FocusState.Active || state is FocusState.OnBreak)) {
            WMCard {
                val left = when (state) {
                    is FocusState.Active -> state.remainingMs
                    is FocusState.OnBreak -> state.sessionRemainingMs
                    else -> 0L
                }
                Text("Focusing" + (s.friendName?.let { " with $it" } ?: "") + " · ${(left + 59_999) / 60_000} min left", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                if (state is FocusState.OnBreak) StatusPill("On a break for ${(state.breakRemainingMs + 59_999) / 60_000} min", Tone.Info)
                DangerButton("End focus", onClick = { vm.store.end() })
            }
        } else {
            WMCard {
                Text("Start a focus session", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                    listOf(15, 25, 45, 60).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") }) }
                }
                PrimaryButton("Start focus", onClick = { vm.startSelf(minutes) }, enabled = serviceOn && chosen.isNotEmpty())
                if (chosen.isEmpty()) Body("Choose at least one app below.")
            }
        }
        if (!serviceOn) {
            WMCard {
                StatusPill("Wake Me focus is off in Accessibility settings", Tone.Warning)
                SecondaryButton("Turn it on", onClick = { disclosing = true })
            }
        }
        SectionTitle("Apps to keep closed")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            vm.apps.forEach { app ->
                FilterChip(selected = app.packageName in chosen, onClick = { vm.toggle(app.packageName); chosen = vm.store.chosenApps }, label = { Text(app.label) })
            }
        }
        Gap(WakeMeTokens.spaceXl)
    }
    if (disclosing) {
        // Prominent disclosure before the system setting (Play's accessibility API policy).
        AlertDialog(
            onDismissRequest = { disclosing = false },
            title = { Text("How Wake Me uses accessibility") },
            text = { Text("Wake Me uses Android's accessibility service only to notice when an app you chose opens during a focus session you started, so it can show the focus screen. It doesn't read what's on your screen, type or tap for you, or send anything about your apps to anyone. You can turn it off in Settings anytime.") },
            confirmButton = { TextButton(onClick = { disclosing = false; runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { disclosing = false }) { Text("Not now") } },
        )
    }
}
