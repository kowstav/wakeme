package app.wakeme.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.alarm.AlarmScheduler
import app.wakeme.core.LocalWindow
import app.wakeme.core.Recurrence
import app.wakeme.data.PlanTerms
import app.wakeme.data.WakeMeApi
import app.wakeme.data.local.Prefs
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.ChallengeDto
import app.wakeme.data.net.PlanDto
import app.wakeme.data.net.PlanVersionDto
import app.wakeme.data.net.OfflineException
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.SettingRow
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.readiness.Readiness
import app.wakeme.sync.SyncEngine
import app.wakeme.sync.SyncWorker
import app.wakeme.ui.grants.DayChips
import app.wakeme.ui.grants.TimeOfDayButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import javax.inject.Inject

/** Literal readiness copy. "Ready" means this phone's OS accepted the alarm, not just that the server saved it. */
fun readinessText(r: String, ownerName: String, mine: Boolean): Pair<String, Tone> {
    val where = if (mine) "this phone" else "$ownerName's phone"
    return when (r) {
        "ready" -> "Ready on $where" to Tone.Success
        "ready_edit_pending" -> "Ready on $where · a change is waiting for approval" to Tone.Info
        "awaiting_acceptance" -> (if (mine) "Waiting for you to accept" else "Waiting for $ownerName to accept") to Tone.Info
        "awaiting_installation" -> "Saved · not yet confirmed on $where" to Tone.Warning
        "install_failed" -> "Couldn't schedule on $where. Check alarm permissions." to Tone.Danger
        "paused" -> "Paused" to Tone.Neutral
        "permission_lost" -> "Permission removed" to Tone.Warning
        "finished" -> "Finished" to Tone.Neutral
        "declined" -> "Declined" to Tone.Neutral
        else -> "Ended" to Tone.Neutral
    }
}

fun daysText(r: Recurrence): String = if (r.kind == "once") "Once on ${r.date}" else {
    val d = r.days.orEmpty().sorted()
    when (d) {
        listOf(1, 2, 3, 4, 5) -> "Weekdays"
        listOf(6, 7) -> "Weekends"
        listOf(1, 2, 3, 4, 5, 6, 7) -> "Every day"
        else -> d.joinToString(", ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
    }
}

data class RoutinesState(val mine: List<PlanDto> = emptyList(), val helping: List<PlanDto> = emptyList(), val myId: String? = null, val exactAlarmsOk: Boolean = true, val error: String? = null)

@HiltViewModel
class RoutinesViewModel @Inject constructor(private val api: WakeMeApi, private val prefs: Prefs, private val scheduler: AlarmScheduler, sync: SyncEngine) : ViewModel() {
    private val _s = MutableStateFlow(RoutinesState())
    val state: StateFlow<RoutinesState> = _s

    init {
        load()
        viewModelScope.launch { sync.changes.collect { if (it == "alarm_plan" || it == "all") load() } }
    }

    fun load() = viewModelScope.launch {
        val me = prefs.currentMe()
        runCatching { api.plans() }
            .onSuccess { _s.value = RoutinesState(it.mine, it.helping, me?.id, scheduler.canScheduleExact()) }
            .onFailure { _s.value = _s.value.copy(myId = me?.id, exactAlarmsOk = scheduler.canScheduleExact(), error = if (it is ApiException) it.message else null) }
    }

    fun get(id: String): PlanDto? = (_s.value.mine + _s.value.helping).firstOrNull { it.id == id }

    fun act(block: suspend WakeMeApi.() -> Unit) = viewModelScope.launch {
        try {
            api.block()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(error = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(error = e.message)
        }
        load()
    }

    /** Local cancellation first, so the phone stops ringing for it even if offline. */
    fun cancel(plan: PlanDto) = viewModelScope.launch {
        scheduler.cancelPlan(plan.id)
        act { cancelPlan(plan.id) }
    }

    fun clearError() {
        _s.value = _s.value.copy(error = null)
    }
}

@Composable
fun RoutinesScreen(onNew: () -> Unit, onOpen: (String) -> Unit, onFixReadiness: () -> Unit = {}, vm: RoutinesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        item {
            Gap()
            Text("Routines", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
            if (!s.exactAlarmsOk) {
                StatusPill("Alarms aren't allowed to ring on time yet", Tone.Danger)
                SecondaryButton("Make this phone ready", onClick = onFixReadiness)
            }
        }
        val proposals = s.mine.filter { it.pending != null }
        if (proposals.isNotEmpty()) item { SectionTitle("Waiting for you") }
        items(proposals, key = { "p" + it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        item { SectionTitle("On this phone") }
        if (s.mine.none { it.accepted != null }) item { Body("No alarms yet. Set one for yourself, or let a friend suggest one.") }
        items(s.mine.filter { it.accepted != null }, key = { it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        if (s.helping.isNotEmpty()) {
            item { SectionTitle("Helping friends") }
            items(s.helping, key = { "h" + it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        }
        item {
            Gap()
            PrimaryButton("New wake-up", onClick = onNew)
            Gap(WakeMeTokens.spaceXl)
        }
    }
    s.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun PlanCard(p: PlanDto, myId: String?, onOpen: (String) -> Unit) {
    val v = p.accepted ?: p.pending ?: return
    val mine = p.owner.id == myId
    val (label, tone) = readinessText(p.readiness, p.owner.displayName, mine)
    WMCard(Modifier.clickable { onOpen(p.id) }) {
        Text("${v.localTime} · ${daysText(v.recurrence)}", style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
        if (v.label.isNotBlank()) Body(v.label)
        if (!mine) Body("For ${p.owner.displayName}") else if (p.creator.id != myId) Body("Suggested by ${p.creator.displayName}")
        StatusPill(label, tone)
    }
}

@Composable
fun RoutineDetailScreen(planId: String, onBack: () -> Unit, vm: RoutinesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val p = vm.get(planId) ?: return
    val mine = p.owner.id == s.myId
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        val (label, tone) = readinessText(p.readiness, p.owner.displayName, mine)
        Text("Wake-up routine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        StatusPill(label, tone)
        p.accepted?.let { VersionCard("Active version", it) }
        p.nextOccurrences.firstOrNull()?.let { Body("Next: ${it.localDate}") }
        p.pending?.let { pending ->
            VersionCard(if (p.accepted == null) "Proposed by ${p.creator.displayName}" else "Proposed change from ${p.creator.displayName}", pending)
            if (mine) {
                Body("Nothing changes until you accept. You can change your mind anytime.")
                PrimaryButton("Accept this version", onClick = { vm.act { acceptPlan(p.id, pending.version) } })
                SecondaryButton(if (p.accepted == null) "Decline" else "Keep the current version", onClick = { vm.act { declinePlan(p.id, pending.version) } })
            }
        }
        if (mine && p.state in setOf("active", "paused")) {
            SecondaryButton(if (p.state == "paused") "Resume" else "Pause", onClick = { vm.act { pausePlan(p.id, p.state != "paused") } })
        }
        if (p.state !in setOf("cancelled", "declined")) DangerButton("End this routine", onClick = { vm.cancel(p); onBack() })
    }
}

@Composable
private fun VersionCard(title: String, v: PlanVersionDto) {
    WMCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        Body("${v.localTime} · ${daysText(v.recurrence)} · ${v.timeZone}${if (v.timeZonePolicy == "device") " (follows your phone when travelling)" else ""}")
        Body("Rings for up to ${v.ringSeconds} seconds with ${v.soundId.removePrefix("bundled:").replace('.', ' ')}")
        v.followUpDelaySeconds?.let { Body("One follow-up alarm ${it / 60} min later unless you finish the challenge") }
        (v.challengePreview as? JsonObject)?.let { pv ->
            Body("Challenge: ${pv["title"]?.jsonPrimitive?.content} · about ${pv["estimatedSeconds"]?.jsonPrimitive?.content}s · ${pv["verificationLabel"]?.jsonPrimitive?.content}")
            Body(pv["escape"]?.jsonPrimitive?.content ?: "")
        }
    }
}

data class EditorState(val saving: Boolean = false, val error: String? = null, val done: Boolean = false, val challenges: List<ChallengeDto> = emptyList())

@HiltViewModel
class RoutineEditorViewModel @Inject constructor(private val api: WakeMeApi, private val readiness: Readiness) : ViewModel() {
    private val _s = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _s

    init {
        viewModelScope.launch { runCatching { api.challenges() }.onSuccess { list -> _s.value = _s.value.copy(challenges = list.filter { it.publishedRevision != null }) } }
    }

    fun save(ownerId: String?, terms: PlanTerms, context: android.content.Context) = viewModelScope.launch {
        _s.value = _s.value.copy(saving = true)
        try {
            api.createPlan(ownerId, terms)
            SyncWorker.runNow(context)
            _s.value = _s.value.copy(saving = false, done = true)
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(saving = false, error = "You're offline. The wake-up wasn't saved.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(saving = false, error = e.message)
        }
    }

    fun exactAlarmsAllowed() = readiness.state().readyForWake

    fun clearError() {
        _s.value = _s.value.copy(error = null)
    }
}

@Composable
fun RoutineEditorScreen(ownerId: String?, onDone: () -> Unit, vm: RoutineEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(s.done) { if (s.done) onDone() }
    var window by remember { mutableStateOf(LocalWindow(listOf(1, 2, 3, 4, 5), "07:00", "07:01")) }
    var label by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf("bundled:rise.gentle") }
    var ring by remember { mutableStateOf(90) }
    var followUp by remember { mutableStateOf(false) }
    var followTravel by remember { mutableStateOf(false) }
    var challengeRevision by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text(if (ownerId == null) "New wake-up" else "Suggest a wake-up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        if (ownerId != null) Body("Your friend sees exactly this and decides. It must fit the wake hours, sounds and limits they allowed.")
        Body("Time and days")
        DayChips(window.days) { window = window.copy(days = it) }
        TimeOfDayButton("Rings at", window.start) { window = window.copy(start = it, end = it) }
        OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label (optional), e.g. 9am class") }, singleLine = true)
        Text("Sound", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            listOf("bundled:rise.gentle", "bundled:bell.bright", "bundled:pulse.wake", "bundled:quack.double").forEach { id ->
                FilterChip(selected = sound == id, onClick = { sound = id }, label = { Text(id.removePrefix("bundled:").replace('.', ' ')) })
            }
        }
        Text("Ring for", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            listOf(30, 60, 90, 120).forEach { v -> FilterChip(selected = ring == v, onClick = { ring = v }, label = { Text("${v}s") }) }
        }
        if (s.challenges.isNotEmpty()) {
            Text("Challenge (optional)", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                FilterChip(selected = challengeRevision == null, onClick = { challengeRevision = null }, label = { Text("None") })
                s.challenges.forEach { c ->
                    val rev = c.revisions.firstOrNull { it.revision == c.publishedRevision }?.id
                    FilterChip(selected = challengeRevision == rev, onClick = { challengeRevision = rev }, label = { Text(c.title) })
                }
            }
            SettingRow("One follow-up alarm", "Rings 3 minutes later unless the challenge is finished. You can always end the session.", followUp) { followUp = it }
        }
        if (ownerId == null) SettingRow("Follow my phone when I travel", "Off keeps the time in your home time zone.", followTravel) { followTravel = it }
        if (!vm.exactAlarmsAllowed()) StatusPill("This phone can't ring alarms on time yet. Allow “Alarms & reminders” first.", Tone.Warning)
        PrimaryButton(if (s.saving) "Saving…" else if (ownerId == null) "Save wake-up" else "Send suggestion", enabled = !s.saving, onClick = {
            vm.save(
                ownerId,
                PlanTerms(
                    label = label,
                    localTime = window.start,
                    timeZone = ZoneId.systemDefault().id,
                    timeZonePolicy = if (followTravel) "device" else "fixed",
                    recurrence = Recurrence.weekly(window.days),
                    soundId = sound,
                    ringSeconds = ring,
                    challengeRevisionId = challengeRevision,
                    followUpDelaySeconds = if (followUp && challengeRevision != null) 180 else null,
                ),
                context,
            )
        })
        QuietButton("Cancel", onClick = onDone)
    }
    s.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(it) }) }
}
