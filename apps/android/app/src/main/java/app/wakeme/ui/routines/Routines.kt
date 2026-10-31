package app.wakeme.ui.routines

import android.content.Context
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
import app.wakeme.designsystem.DisclosureGroup
import app.wakeme.designsystem.Loading
import app.wakeme.designsystem.AdaptiveList
import app.wakeme.designsystem.ReloadWhenBackOnTop
import app.wakeme.designsystem.fullWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.HeroCard
import app.wakeme.designsystem.IconChip
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMIconView
import app.wakeme.designsystem.WakeMeFamily
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.CardTitle
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.FieldGroup
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.WakeMeType
import androidx.compose.foundation.layout.fillMaxWidth
import app.wakeme.designsystem.DetailScreen
import app.wakeme.designsystem.DetailTopBar
import app.wakeme.designsystem.EmptyState
import app.wakeme.designsystem.TimeDisplayStyle
import app.wakeme.ui.grants.TimeDisplay
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
        "install_failed" -> "Couldn't schedule on $where. Check alarm permissions." to Tone.Warning
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
    androidx.compose.material3.Scaffold(
        containerColor = WM.colors.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = onNew,
                shape = androidx.compose.foundation.shape.CircleShape,
                containerColor = WM.colors.accent,
                contentColor = WM.colors.textOnAccent,
            ) {
                WMIconView(WMIcon.Plus, tint = WM.colors.textOnAccent, contentDescription = "New wake-up")
            }
        },
    ) { listInsets ->
    AdaptiveList(Modifier.padding(listInsets).padding(horizontal = WakeMeTokens.spaceMd),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        fullWidth {
            Gap()
            Text("Routines", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            if (!s.exactAlarmsOk) {
                Gap(WakeMeTokens.spaceXs)
                StatusPill("Alarms aren't allowed to ring on time yet", Tone.Warning, icon = WMIcon.Warning)
                Gap(WakeMeTokens.spaceXs)
                SecondaryButton("Make this phone ready", onClick = onFixReadiness, icon = WMIcon.Phone)
            }
            if (s.pendingEnds > 0) StatusPill("Ended on this phone · account update waiting to sync", Tone.Warning, icon = WMIcon.Clock)
        }
        val proposals = s.mine.filter { it.pending != null }
        if (proposals.isNotEmpty()) fullWidth { SectionTitle("Waiting for you") }
        items(proposals, key = { "p" + it.id }) { p -> PlanCard(p, s.myId, onOpen, Modifier.animateItem()) }
        fullWidth { SectionTitle("On this phone") }
        if (s.mine.none { it.accepted != null }) fullWidth { EmptyState("No alarms yet") }
        items(s.mine.filter { it.accepted != null }, key = { it.id }) { p -> PlanCard(p, s.myId, onOpen, Modifier.animateItem()) }
        if (s.helping.isNotEmpty()) {
            fullWidth { SectionTitle("Helping friends") }
            items(s.helping, key = { "h" + it.id }) { p -> PlanCard(p, s.myId, onOpen, Modifier.animateItem()) }
        }

    }
    }
}

@Composable
private fun PlanCard(p: PlanDto, myId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val v = p.accepted ?: p.pending ?: return
    val mine = p.owner.id == myId
    val (label, tone) = readinessText(p.readiness, p.owner.displayName, mine)
    WMCard(modifier, onClick = { onOpen(p.id) }, onClickLabel = "Open routine", container = WM.colors.heroFill) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.layoutTitleGap)) {
                Text(v.localTime, style = MaterialTheme.typography.displaySmall, color = WM.colors.onHero)
                Supporting(daysText(v.recurrence) + if (v.label.isBlank()) "" else " · ${v.label}", color = WM.colors.onHeroSecondary)
                if (!mine) Supporting("For ${p.owner.displayName}", color = WM.colors.onHeroSecondary) else if (p.creator.id != myId) Supporting("Suggested by ${p.creator.displayName}", color = WM.colors.onHeroSecondary)
            }
            val (icon, family) = timeOfDay(v.localTime)
            IconChip(icon, family, size = 48.dp)
        }
        StatusPill(label, tone, announce = true, icon = toneIcon(tone))
    }
}

/**
 * A routine's time of day as an icon in its own petal colour: mornings are a gold sun, afternoons
 * an apricot sun, evenings and nights a periwinkle moon. Decorative; the time itself is the text.
 */
@Composable
internal fun timeOfDay(localTime: String): Pair<WMIcon, WakeMeFamily> {
    val hour = localTime.substringBefore(':').toIntOrNull() ?: 7
    val p = WM.palette
    return when (hour) {
        in 4..11 -> WMIcon.Sun to p.gold
        in 12..16 -> WMIcon.Sun to p.apricot
        else -> WMIcon.Moon to p.periwinkle
    }
}

/** The icon a status pill leads with, so its tone is a shape as well as a colour. */
internal fun toneIcon(tone: Tone): WMIcon? = when (tone) {
    Tone.Success -> WMIcon.CheckCircle
    Tone.Warning -> WMIcon.Warning
    Tone.Info -> WMIcon.Info
    Tone.Danger -> WMIcon.Warning
    Tone.Neutral -> null
}

/**
 * "07:00 · Weekdays" as one text (tests and TalkBack read it whole), with the time in the
 * timeInline role and the days quieter: the time is what people look for.
 */
private fun timeAndDays(time: String, days: String, quiet: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    withStyle(WakeMeType.timeInline.toSpanStyle()) { append(time) }
    withStyle(SpanStyle(color = quiet)) {
        append(" · ")
        append(days)
    }
}

@Composable
fun RoutineDetailScreen(planId: String, onBack: () -> Unit, onReuse: () -> Unit = {}, vm: RoutinesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    // Read the observed state before the loading return, so the first API result recomposes this pane.
    val p = (s.mine + s.helping).firstOrNull { it.id == planId } ?: run { Loading(); return }
    val mine = p.owner.id == s.myId
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        DetailTopBar(onBack = onBack, modifier = Modifier.padding(horizontal = WakeMeTokens.spaceXxs))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WakeMeTokens.spaceMd).padding(bottom = WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
            val (label, tone) = readinessText(p.readiness, p.owner.displayName, mine)
            Text("Wake-up routine", style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            (p.accepted ?: p.pending)?.let { v ->
                HeroCard(Modifier.semantics(mergeDescendants = true) { }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.localTime, style = TimeDisplayStyle, color = WM.colors.onHero, modifier = Modifier.weight(1f))
                        WMIconView(timeOfDay(v.localTime).first, tint = WM.colors.onHero, size = WakeMeTokens.iconSizeLarge)
                    }
                    Text(daysText(v.recurrence) + if (v.label.isNotBlank()) " · ${v.label}" else "", style = MaterialTheme.typography.titleMedium, color = WM.colors.onHeroSecondary)
                }
            }
            StatusPill(label, tone, announce = true, icon = toneIcon(tone))
            p.accepted?.let { VersionCard("Active version", it) }
            p.nextOccurrences.firstOrNull()?.let { Body("Next: ${it.localDate}") }
            p.pending?.let { pending ->
                VersionCard(if (p.accepted == null) "Proposed by ${p.creator.displayName}" else "Proposed change from ${p.creator.displayName}", pending)
                if (mine) {
                    PrimaryButton("Accept this version", onClick = { vm.act({ acceptPlan(p.id, pending.version) }) }, icon = WMIcon.Check)
                    SecondaryButton(if (p.accepted == null) "Decline" else "Keep the current version", onClick = { vm.act({ declinePlan(p.id, pending.version) }) }, icon = WMIcon.Close)
                }
            }
            if (mine && p.state in setOf("active", "paused")) {
                SecondaryButton(if (p.state == "paused") "Resume" else "Pause", onClick = { vm.pause(p, p.state != "paused") }, icon = if (p.state == "paused") WMIcon.Play else WMIcon.Pause)
            }
            if (p.accepted != null) SecondaryButton("Use this routine again", onClick = onReuse, icon = WMIcon.Repeat)
            if (p.state !in setOf("cancelled", "declined")) DangerButton("End this routine", onClick = { vm.cancel(p); onBack() }, icon = WMIcon.Trash)
        }
    }
}

@Composable
private fun VersionCard(title: String, v: PlanVersionDto) {
    WMCard {
        CardTitle(title)
        DetailLine(WMIcon.Globe, "${v.localTime} · ${daysText(v.recurrence)} · ${v.timeZone}${if (v.timeZonePolicy == "device") " (follows your phone when travelling)" else ""}")
        DetailLine(WMIcon.Speaker, "Rings for up to ${v.ringSeconds} seconds with ${soundName(v.soundId).lowercase()}")
        v.followUpDelaySeconds?.let { DetailLine(WMIcon.Repeat, "One follow-up alarm ${it / 60} min later unless you finish the challenge") }
        (v.challengePreview as? JsonObject)?.let { pv ->
            DetailLine(WMIcon.Trophy, "Challenge: ${pv["title"]?.jsonPrimitive?.content} · about ${pv["estimatedSeconds"]?.jsonPrimitive?.content}s · ${pv["verificationLabel"]?.jsonPrimitive?.content}")
            Body(pv["escape"]?.jsonPrimitive?.content ?: "")
        }
    }
}

/** One fact about a routine with its icon, so a card of facts can be scanned instead of read. */
@Composable
private fun DetailLine(icon: WMIcon, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        WMIconView(icon, tint = WM.colors.textSecondary, size = WakeMeTokens.iconSizeSmall, modifier = Modifier.padding(top = 2.dp))
        Body(text, modifier = Modifier.weight(1f))
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
        if (_s.value.saving) return@launch
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
            selectedPreset = ""
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
    DetailScreen(onBack = onDone) {
        Text(if (ownerId == null) "New wake-up" else "Suggest a wake-up", style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
        TimeDisplay("Rings at", window.start) { window = window.copy(start = it, end = it) }
        DayChips(window.days) { window = window.copy(days = it) }

        if (limits != null && ownerId != null) {
            Body("${s.friendName ?: "They"} allow wake-ups: " + limits.windows.joinToString("; ") { "${daysText(Recurrence.weekly(it.days))} ${it.start}–${it.end}" } + ". It rings at this time in their time zone.")
            if (outsideHours) StatusPill("That's outside the wake hours ${s.friendName ?: "they"} chose.", Tone.Warning)
        }
        OutlinedTextField(label, { label = it.take(40) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FieldGroup("Sound") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                sounds.forEach { id ->
                    ChoiceChip(soundName(id), selected = sound == id, onClick = { sound = id })
                }
            }
            if (sound.startsWith("media:")) Supporting("If this recording isn't available, Gentle rise rings instead. Recordings aren't available before the first unlock after restarting your phone.")
        }
        DisclosureGroup("More options", if (guest) "Routine template, duration and travel" else "Duration, challenges and travel") {
        FieldGroup("Routine template") { FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            listOf("Wake-up" to WMIcon.Sun, "Study" to WMIcon.Book, "Gym" to WMIcon.Dumbbell, "Language practice" to WMIcon.Language, "Music practice" to WMIcon.Music).forEach { (preset, icon) ->
                ChoiceChip(preset, selected = selectedPreset == preset, icon = icon, onClick = {
                    selectedPreset = preset
                    label = if (preset == "Wake-up") "" else preset
                    ring = if (preset == "Wake-up") 90 else 30
                    if (ownerId != null) ring = minOf(ring, limits?.maxAlarmSeconds ?: ring)
                    if (ownerId == null) {
                        val at = if (preset == "Wake-up" || preset == "Gym") "07:00" else "18:00"
                        window = LocalWindow(if (preset == "Gym") listOf(1, 3, 5) else listOf(1, 2, 3, 4, 5), at, at)
                        timeZone = ZoneId.systemDefault().id
                    }
                })
            }
        } }
        FieldGroup("Ring for") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                rings.forEach { v -> ChoiceChip("${v}s", selected = ring == v, onClick = { ring = v }) }
            }
        }
        if (s.challenges.isNotEmpty() && (ownerId == null || s.canAssignChallenges)) {
            FieldGroup("Challenge (optional)") { FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                ChoiceChip("None", selected = challengeRevision == null, onClick = { challengeRevision = null })
                s.challenges.forEach { c ->
                    val rev = c.revisions.firstOrNull { it.revision == c.publishedRevision }?.id
                    ChoiceChip(c.title, selected = challengeRevision == rev, onClick = { challengeRevision = rev })
                }
            } }
            if (canFollowUp) SettingRow("One follow-up alarm", "Rings ${followUpDelay / 60} min later unless the challenge is finished. You can always end the session.", followUp) { followUp = it }
        }
        if (ownerId == null) SettingRow("Follow my phone when I travel", "Off keeps the time in your home time zone.", followTravel) { followTravel = it }
        }
        if (!vm.exactAlarmsAllowed()) StatusPill("This phone can't ring alarms on time yet. Allow “Alarms & reminders” first.", Tone.Warning, icon = WMIcon.Warning)
        PrimaryButton(if (s.saving) "Saving…" else if (ownerId == null) "Save wake-up" else "Send suggestion", icon = if (ownerId == null) WMIcon.Check else WMIcon.Send, enabled = !s.saving && (reuseId == null || s.reuseChecked) && !outsideHours && sound in sounds && ring in rings, onClick = {
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
