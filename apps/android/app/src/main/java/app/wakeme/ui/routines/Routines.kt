package app.wakeme.ui.routines

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.wakeme.designsystem.AdaptiveList
import app.wakeme.designsystem.ReloadWhenBackOnTop
import app.wakeme.designsystem.fullWidth
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.alarm.AlarmScheduler
import app.wakeme.core.AlarmLimits
import app.wakeme.core.Capability
import app.wakeme.core.LocalWindow
import app.wakeme.core.Recurrence
import app.wakeme.core.isWithinAnyWindow
import app.wakeme.data.PlanTerms
import app.wakeme.data.WakeMeApi
import app.wakeme.data.local.LocalDb
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
import app.wakeme.ui.soundName
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class RoutinesState(val mine: List<PlanDto> = emptyList(), val helping: List<PlanDto> = emptyList(), val myId: String? = null, val exactAlarmsOk: Boolean = true, val error: String? = null, val pendingEnds: Int = 0)

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WakeMeApi,
    private val prefs: Prefs,
    private val scheduler: AlarmScheduler,
    private val db: LocalDb,
    sync: SyncEngine,
) : ViewModel() {
    private val _s = MutableStateFlow(RoutinesState())
    val state: StateFlow<RoutinesState> = _s

    init {
        load()
        viewModelScope.launch { sync.changes.collect { if (it == "alarm_plan" || it == "all") load() } }
    }

    fun load() = viewModelScope.launch {
        val me = prefs.currentMe()
        val boot = app.wakeme.alarm.BootAlarms(context)
        runCatching { api.plans() }
            .onSuccess { _s.value = RoutinesState(it.mine.filterNot { p -> boot.isEnded(p.id) }, it.helping.filterNot { p -> boot.isEnded(p.id) }, me?.id, scheduler.canScheduleExact(), pendingEnds = me?.id?.let { id -> boot.pendingEnds(id).size } ?: 0) }
            .onFailure { _s.value = _s.value.copy(myId = me?.id, exactAlarmsOk = scheduler.canScheduleExact(), pendingEnds = me?.id?.let { id -> boot.pendingEnds(id).size } ?: 0, error = if (it is ApiException) it.message else null) }
    }

    fun get(id: String): PlanDto? = (_s.value.mine + _s.value.helping).firstOrNull { it.id == id }

    /**
     * Runs a routine action on the server, then [onDone] on this phone, then a sync so the
     * phone arms or removes it now rather than at the next periodic sync.
     */
    fun act(block: suspend WakeMeApi.() -> Unit, onDone: suspend () -> Unit = {}) = viewModelScope.launch {
        try {
            api.block()
            onDone()
            SyncWorker.runNow(context)
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(error = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(error = e.message)
        }
        load()
    }

    /** A durable local stop comes first; sync retries the account mutation under the same identity. */
    fun cancel(plan: PlanDto) = viewModelScope.launch {
        val account = prefs.currentMe()?.id ?: return@launch
        val boot = app.wakeme.alarm.BootAlarms(context)
        boot.endPlan(plan.id, account)
        _s.value = _s.value.copy(mine = _s.value.mine.filterNot { it.id == plan.id }, helping = _s.value.helping.filterNot { it.id == plan.id }, pendingEnds = boot.pendingEnds(account).size)
        if (app.wakeme.alarm.AlarmEntryPoints.sessions(context).active.value?.planId == plan.id) app.wakeme.alarm.AlarmService.send(context, app.wakeme.alarm.AlarmService.ACTION_ESCAPE)
        try { removeLocally(plan.id) } finally { SyncWorker.runNow(context) }
    }

    fun pause(plan: PlanDto, paused: Boolean) = act({ pausePlan(plan.id, paused) }) { if (paused) removeLocally(plan.id) }

    private suspend fun removeLocally(planId: String) {
        scheduler.cancelPlan(planId)
        db.routines().delete(planId)
    }

    fun clearError() {
        _s.value = _s.value.copy(error = null)
    }
}

@Composable
fun RoutinesScreen(onNew: () -> Unit, onOpen: (String) -> Unit, onFixReadiness: () -> Unit = {}, onTop: Boolean = true, vm: RoutinesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    ReloadWhenBackOnTop(onTop, vm::load)
    RoutinesList(s, onNew = onNew, onOpen = onOpen, onFixReadiness = onFixReadiness)
    s.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(it) }) }
}

/** The Routines tab's content without its ViewModel (the screenshot tests render it with sample data). */
@Composable
fun RoutinesList(s: RoutinesState, onNew: () -> Unit, onOpen: (String) -> Unit, onFixReadiness: () -> Unit) {
    AdaptiveList(Modifier.padding(horizontal = WakeMeTokens.spaceMd), contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()) {
        fullWidth {
            Gap()
            Text("Routines", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            if (!s.exactAlarmsOk) {
                StatusPill("Alarms aren't allowed to ring on time yet", Tone.Danger)
                SecondaryButton("Make this phone ready", onClick = onFixReadiness)
            }
            if (s.pendingEnds > 0) StatusPill("Ended on this phone · account update waiting to sync", Tone.Warning)
        }
        val proposals = s.mine.filter { it.pending != null }
        if (proposals.isNotEmpty()) fullWidth { SectionTitle("Waiting for you") }
        items(proposals, key = { "p" + it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        fullWidth { SectionTitle("On this phone") }
        if (s.mine.none { it.accepted != null }) fullWidth { Body("No alarms yet. Set one for yourself, or let a friend suggest one.") }
        items(s.mine.filter { it.accepted != null }, key = { it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        if (s.helping.isNotEmpty()) {
            fullWidth { SectionTitle("Helping friends") }
            items(s.helping, key = { "h" + it.id }) { p -> PlanCard(p, s.myId, onOpen) }
        }
        fullWidth {
            Gap()
            PrimaryButton("New wake-up", onClick = onNew)
            Gap(WakeMeTokens.spaceXl)
        }
    }
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
fun RoutineDetailScreen(planId: String, onBack: () -> Unit, onReuse: () -> Unit = {}, vm: RoutinesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    // Read the observed state before the loading return, so the first API result recomposes this pane.
    val p = (s.mine + s.helping).firstOrNull { it.id == planId } ?: run { Body("Loading routine…"); return }
    val mine = p.owner.id == s.myId
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        val (label, tone) = readinessText(p.readiness, p.owner.displayName, mine)
        Text("Wake-up routine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        StatusPill(label, tone)
        p.accepted?.let { VersionCard("Active version", it) }
        p.nextOccurrences.firstOrNull()?.let { Body("Next: ${it.localDate}") }
        p.pending?.let { pending ->
            VersionCard(if (p.accepted == null) "Proposed by ${p.creator.displayName}" else "Proposed change from ${p.creator.displayName}", pending)
            if (mine) {
                Body("Nothing changes until you accept. You can change your mind anytime.")
                PrimaryButton("Accept this version", onClick = { vm.act({ acceptPlan(p.id, pending.version) }) })
                SecondaryButton(if (p.accepted == null) "Decline" else "Keep the current version", onClick = { vm.act({ declinePlan(p.id, pending.version) }) })
            }
        }
        if (mine && p.state in setOf("active", "paused")) {
            SecondaryButton(if (p.state == "paused") "Resume" else "Pause", onClick = { vm.pause(p, p.state != "paused") })
        }
        if (p.accepted != null) SecondaryButton("Use this routine again", onClick = onReuse)
        if (p.state !in setOf("cancelled", "declined")) DangerButton("End this routine", onClick = { vm.cancel(p); onBack() })
    }
}

@Composable
private fun VersionCard(title: String, v: PlanVersionDto) {
    WMCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        Body("${v.localTime} · ${daysText(v.recurrence)} · ${v.timeZone}${if (v.timeZonePolicy == "device") " (follows your phone when travelling)" else ""}")
        Body("Rings for up to ${v.ringSeconds} seconds with ${soundName(v.soundId).lowercase()}")
        v.followUpDelaySeconds?.let { Body("One follow-up alarm ${it / 60} min later unless you finish the challenge") }
        (v.challengePreview as? JsonObject)?.let { pv ->
            Body("Challenge: ${pv["title"]?.jsonPrimitive?.content} · about ${pv["estimatedSeconds"]?.jsonPrimitive?.content}s · ${pv["verificationLabel"]?.jsonPrimitive?.content}")
            Body(pv["escape"]?.jsonPrimitive?.content ?: "")
        }
    }
}

data class EditorState(
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val challenges: List<ChallengeDto> = emptyList(),
    /** For a suggestion: the friend's name and what they allowed (wake hours, sounds, ring length, follow-up). */
    val friendName: String? = null,
    val limits: AlarmLimits? = null,
    val canAssignChallenges: Boolean = true,
    val reuse: PlanVersionDto? = null,
    val reuseChecked: Boolean = false,
)

@HiltViewModel
class RoutineEditorViewModel @Inject constructor(private val api: WakeMeApi, private val readiness: Readiness, private val local: app.wakeme.alarm.LocalRoutines, val sounds: app.wakeme.media.SoundLibrary) : ViewModel() {
    private val _s = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _s

    init {
        viewModelScope.launch { runCatching { api.challenges() }.onSuccess { list -> _s.value = _s.value.copy(challenges = list.filter { it.publishedRevision != null }) } }
        viewModelScope.launch { runCatching { sounds.refresh() } }
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

    /** Guest use: stored and armed on this phone only; no account, no server. */
    fun saveLocal(terms: PlanTerms) = viewModelScope.launch {
        _s.value = _s.value.copy(saving = true)
        local.save(terms)
        _s.value = _s.value.copy(saving = false, done = true)
    }

    fun exactAlarmsAllowed() = readiness.state().readyForWake

    fun loadReusable(id: String) = viewModelScope.launch {
        runCatching { api.plan(id).accepted }.onSuccess { _s.value = _s.value.copy(reuse = it, reuseChecked = true) }
            .onFailure { _s.value = _s.value.copy(reuseChecked = true, error = "Couldn't load that routine. You can still make a new one.") }
    }

    /** A suggestion stays inside what the friend allowed; their grant says what that is. */
    fun loadFriend(ownerId: String) = viewModelScope.launch {
        runCatching { api.friends() }.getOrNull()?.firstOrNull { it.user.id == ownerId }?.let { f ->
            val grant = f.theyAllowMe
            _s.value = _s.value.copy(friendName = f.user.displayName, limits = grant?.limits?.alarm, canAssignChallenges = grant?.capabilities?.contains(Capability.CHALLENGE_ASSIGN) == true)
        }
    }

    fun clearError() {
        _s.value = _s.value.copy(error = null)
    }
}

private val RoutineWindowSaver = listSaver<LocalWindow, String>(
    save = { listOf(it.days.joinToString(","), it.start, it.end) },
    restore = { LocalWindow(it[0].split(',').map(String::toInt), it[1], it[2]) },
)

@Composable
fun RoutineEditorScreen(ownerId: String?, onDone: () -> Unit, guest: Boolean = false, reuseId: String? = null, vm: RoutineEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val customSounds by vm.sounds.ready.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(s.done) { if (s.done) onDone() }
    LaunchedEffect(ownerId) { ownerId?.let(vm::loadFriend) }
    LaunchedEffect(reuseId) { reuseId?.let(vm::loadReusable) }
    var window by rememberSaveable(stateSaver = RoutineWindowSaver) { mutableStateOf(LocalWindow(listOf(1, 2, 3, 4, 5), "07:00", "07:01")) }
    var label by rememberSaveable { mutableStateOf("") }
    var sound by rememberSaveable { mutableStateOf("bundled:rise.gentle") }
    var ring by rememberSaveable { mutableStateOf(90) }
    var followUp by rememberSaveable { mutableStateOf(false) }
    var followTravel by rememberSaveable { mutableStateOf(false) }
    var challengeRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPreset by rememberSaveable { mutableStateOf("Wake-up") }
    var reused by rememberSaveable(reuseId) { mutableStateOf(false) }
    var timeZone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var appliedLimits by rememberSaveable(ownerId) { mutableStateOf(false) }
    val limits = s.limits
    val sounds = if (ownerId == null) listOf("bundled:rise.gentle", "bundled:bell.bright", "bundled:pulse.wake", "bundled:quack.double") + (if (guest) emptyList() else customSounds) else limits?.sounds.orEmpty()
    val rings = (listOf(30, 60, 90, 120) + ring).distinct().sorted().filter { ownerId == null || limits == null || it <= limits.maxAlarmSeconds }.ifEmpty { listOfNotNull(limits?.maxAlarmSeconds) }
    val followUpDelay = minOf(180, if (ownerId == null) 180 else limits?.followUp?.maxDelaySeconds ?: 180)
    val canFollowUp = ownerId == null || limits?.followUp?.allowed == true
    LaunchedEffect(s.reuse, sounds, s.challenges) {
        val source = s.reuse
        if (source != null && !reused) {
            label = source.label
            window = LocalWindow(source.recurrence.days ?: listOf(1, 2, 3, 4, 5), source.localTime, source.localTime)
            sound = source.soundId.takeIf { it in sounds } ?: "bundled:rise.gentle"
            ring = source.ringSeconds
            timeZone = source.timeZone
            followTravel = source.timeZonePolicy == "device"
            // The new owner chooses a challenge they can still access; sharing never copies authority.
            challengeRevision = null
            followUp = false
            reused = true
        }
    }
    // A suggestion must ring inside the friend's wake hours on every chosen day (the server checks too).
    val outsideHours = ownerId != null && limits != null && !window.days.all { isWithinAnyWindow(window.start, it, limits.windows) }
    // Start from choices inside what they allowed.
    LaunchedEffect(limits) {
        if (ownerId == null || limits == null || appliedLimits) return@LaunchedEffect
        limits.sounds.firstOrNull()?.let { sound = it }
        if (ring !in rings) ring = rings.last()
        limits.windows.firstOrNull()?.let { w ->
            val days = window.days.filter { it in w.days }.ifEmpty { w.days }
            window = window.copy(days = days)
            if (!days.all { isWithinAnyWindow(window.start, it, limits.windows) }) window = window.copy(start = w.start, end = w.start)
        }
        appliedLimits = true
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text(if (ownerId == null) "New wake-up" else "Suggest a wake-up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        if (ownerId != null) Body("${s.friendName ?: "Your friend"} sees exactly this and decides. Nothing changes on their phone until they accept.")
        Body("Use routines for waking, study, gym or practice. These are reminders you choose; Wake Me doesn't verify attendance or penalize missed days.")
        if (reuseId != null) Body("This creates a new routine for you. Your existing routine and your friends' permissions stay as they are. Choose any challenge again.")
        Text("Start with", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            listOf("Wake-up", "Study", "Gym", "Language practice", "Music practice").forEach { preset ->
                FilterChip(selected = selectedPreset == preset, onClick = {
                    selectedPreset = preset
                    label = if (preset == "Wake-up") "" else preset
                    ring = if (preset == "Wake-up") 90 else 30
                    if (ownerId != null) ring = minOf(ring, limits?.maxAlarmSeconds ?: ring)
                    if (ownerId == null) {
                        val at = if (preset == "Wake-up" || preset == "Gym") "07:00" else "18:00"
                        window = LocalWindow(if (preset == "Gym") listOf(1, 3, 5) else listOf(1, 2, 3, 4, 5), at, at)
                        timeZone = ZoneId.systemDefault().id
                    }
                }, label = { Text(preset) })
            }
        }
        Body("Time and days")
        DayChips(window.days) { window = window.copy(days = it) }
        TimeOfDayButton("Rings at", window.start) { window = window.copy(start = it, end = it) }
        if (ownerId == null) Body("Time zone: $timeZone")
        if (limits != null && ownerId != null) {
            Body("${s.friendName ?: "They"} allow wake-ups: " + limits.windows.joinToString("; ") { "${daysText(Recurrence.weekly(it.days))} ${it.start}–${it.end}" } + ". It rings at this time in their time zone.")
            if (outsideHours) StatusPill("That's outside the wake hours ${s.friendName ?: "they"} chose.", Tone.Warning)
        }
        OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label (optional), e.g. 9am class") }, singleLine = true)
        Text("Sound", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            sounds.forEach { id ->
                FilterChip(selected = sound == id, onClick = { sound = id }, label = { Text(soundName(id)) })
            }
        }
        Text("Ring for", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
        if (sound.startsWith("media:")) Body("If this recording isn't available, Gentle rise rings instead. Recordings aren't available before the first unlock after restarting your phone.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            rings.forEach { v -> FilterChip(selected = ring == v, onClick = { ring = v }, label = { Text("${v}s") }) }
        }
        if (ownerId != null) Body("Only the sounds and ring length ${s.friendName ?: "they"} approved.")
        if (s.challenges.isNotEmpty() && (ownerId == null || s.canAssignChallenges)) {
            Text("Challenge (optional)", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                FilterChip(selected = challengeRevision == null, onClick = { challengeRevision = null }, label = { Text("None") })
                s.challenges.forEach { c ->
                    val rev = c.revisions.firstOrNull { it.revision == c.publishedRevision }?.id
                    FilterChip(selected = challengeRevision == rev, onClick = { challengeRevision = rev }, label = { Text(c.title) })
                }
            }
            if (canFollowUp) SettingRow("One follow-up alarm", "Rings ${followUpDelay / 60} min later unless the challenge is finished. You can always end the session.", followUp) { followUp = it }
        }
        if (ownerId == null) SettingRow("Follow my phone when I travel", "Off keeps the time in your home time zone.", followTravel) { followTravel = it }
        if (!vm.exactAlarmsAllowed()) StatusPill("This phone can't ring alarms on time yet. Allow “Alarms & reminders” first.", Tone.Warning)
        PrimaryButton(if (s.saving) "Saving…" else if (ownerId == null) "Save wake-up" else "Send suggestion", enabled = !s.saving && (reuseId == null || s.reuseChecked) && !outsideHours && sound in sounds && ring in rings, onClick = {
            val terms =
                PlanTerms(
                    label = label,
                    localTime = window.start,
                    timeZone = timeZone,
                    timeZonePolicy = if (followTravel) "device" else "fixed",
                    recurrence = Recurrence.weekly(window.days),
                    soundId = sound,
                    ringSeconds = ring,
                    challengeRevisionId = challengeRevision,
                    followUpDelaySeconds = if (followUp && canFollowUp && challengeRevision != null) followUpDelay else null,
                )
            if (guest) vm.saveLocal(terms) else vm.save(ownerId, terms, context)
        })
        QuietButton("Cancel", onClick = onDone)
    }
    s.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(it) }) }
}
