package app.wakeme.ui.grants

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.CardTitle
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.FieldGroup
import app.wakeme.designsystem.HeroCard
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMIconView
import app.wakeme.designsystem.quietFill
import androidx.compose.ui.graphics.Color
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.TimeDisplayStyle
import app.wakeme.designsystem.WMMotion
import app.wakeme.designsystem.rememberHaptics
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.core.AlarmLimits
import app.wakeme.core.Capability
import app.wakeme.core.ChallengeLimits
import app.wakeme.core.CheckinLimits
import app.wakeme.core.FollowUp
import app.wakeme.core.GrantLimits
import app.wakeme.core.LocalWindow
import app.wakeme.core.NudgeLimits
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.FriendDto
import app.wakeme.data.net.PresetDto
import app.wakeme.data.net.OfflineException
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.Note
import app.wakeme.designsystem.DetailScreen
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.SettingRow
import app.wakeme.designsystem.Stepper
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.ui.soundName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.StatusPill
import java.time.format.DateTimeFormatter
import java.time.ZoneId

private val BUNDLED = listOf("bundled:quack.classic", "bundled:quack.double", "bundled:chime.soft", "bundled:bell.bright", "bundled:rise.gentle", "bundled:pulse.wake")
private val ALL_DAYS = listOf(1, 2, 3, 4, 5, 6, 7)
private val CHALLENGE_TYPES = listOf("multiple_choice", "short_answer", "math", "memory_order", "chess", "timer", "qr_code", "self_attested")

data class Draft(
    val caps: Set<String> = emptySet(),
    val nudge: NudgeLimits = NudgeLimits(3, listOf(LocalWindow(ALL_DAYS, "08:00", "22:00")), listOf("bundled:quack.classic", "bundled:quack.double"), false),
    val alarm: AlarmLimits = AlarmLimits(120, 1, listOf(LocalWindow(listOf(1, 2, 3, 4, 5), "06:00", "10:00")), listOf("bundled:rise.gentle", "bundled:bell.bright"), FollowUp(true, 300)),
    val challenge: ChallengeLimits = ChallengeLimits(listOf("multiple_choice", "short_answer", "math", "memory_order", "chess"), 90, "medium"),
    val checkin: CheckinLimits = CheckinLimits(2),
    val validDays: Long = 90,
) {
    fun limits() = GrantLimits(
        nudge = nudge.takeIf { Capability.NUDGE in caps },
        alarm = alarm.takeIf { Capability.ALARM_REQUEST in caps || Capability.ALARM_SCHEDULE in caps },
        challenge = challenge.takeIf { Capability.CHALLENGE_ASSIGN in caps },
        checkin = checkin.takeIf { Capability.CHECKIN in caps },
    )
}

data class GrantEditorState(val friend: FriendDto? = null, val presets: List<PresetDto> = emptyList(), val draft: Draft = Draft(), val saving: Boolean = false, val error: String? = null, val saved: Boolean = false)

@HiltViewModel
class GrantEditorViewModel @Inject constructor(private val api: WakeMeApi, val sounds: app.wakeme.media.SoundLibrary) : ViewModel() {
    private val _s = MutableStateFlow(GrantEditorState())
    val state: StateFlow<GrantEditorState> = _s

    fun load(friendshipId: String) = viewModelScope.launch {
        runCatching { sounds.refresh() }
        runCatching {
            val f = api.friend(friendshipId)
            val presets = api.presets()
            val existing = f.iAllowThem
            val draft = if (existing == null) Draft() else Draft(
                caps = existing.capabilities.toSet(),
                nudge = existing.limits.nudge ?: Draft().nudge,
                alarm = existing.limits.alarm ?: Draft().alarm,
                challenge = existing.limits.challenge ?: Draft().challenge,
                checkin = existing.limits.checkin ?: Draft().checkin,
            )
            _s.value = GrantEditorState(friend = f, presets = presets, draft = draft)
        }.onFailure { _s.value = _s.value.copy(error = it.message) }
    }

    fun applyPreset(p: PresetDto) = edit {
        Draft(
            caps = p.capabilities.toSet() - Capability.FOCUS,
            nudge = p.limits.nudge ?: it.nudge,
            alarm = p.limits.alarm ?: it.alarm,
            challenge = p.limits.challenge ?: it.challenge,
            checkin = p.limits.checkin ?: it.checkin,
            validDays = it.validDays,
        )
    }

    fun edit(f: (Draft) -> Draft) {
        _s.value = _s.value.copy(draft = f(_s.value.draft))
    }

    fun toggle(cap: String, on: Boolean) = edit { it.copy(caps = if (on) it.caps + cap else it.caps - cap) }

    fun save() = viewModelScope.launch {
        val s = _s.value
        val f = s.friend ?: return@launch
        if (s.draft.caps.isEmpty()) {
            _s.value = s.copy(error = "Choose at least one thing, or remove all permissions instead.")
            return@launch
        }
        _s.value = s.copy(saving = true)
        val until = Instant.now().plus(s.draft.validDays, ChronoUnit.DAYS)
        try {
            val existing = f.iAllowThem
            if (existing == null) api.createGrant(f.user.id, s.draft.caps.toList(), s.draft.limits(), until)
            else api.updateGrant(existing.id, existing.version, s.draft.caps.toList(), s.draft.limits(), until, existing.paused)
            _s.value = _s.value.copy(saving = false, saved = true)
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(saving = false, error = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            val msg = if (e.code == "reauth_required") "For your safety, sign in again to allow wake alarms or location requests." else e.message
            _s.value = _s.value.copy(saving = false, error = msg)
        }
    }

    fun revokeAll() = viewModelScope.launch {
        _s.value.friend?.iAllowThem?.let { g -> runCatching { api.revokeGrant(g.id) }.onSuccess { _s.value = _s.value.copy(saved = true) } }
    }

    fun clearError() {
        _s.value = _s.value.copy(error = null)
    }
}

@Composable
fun GrantEditorScreen(friendshipId: String, onDone: () -> Unit, vm: GrantEditorViewModel = hiltViewModel()) {
    LaunchedEffect(friendshipId) { vm.load(friendshipId) }
    val s by vm.state.collectAsState()
    val customSounds by vm.sounds.ready.collectAsState()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    val f = s.friend ?: return
    val name = f.user.displayName
    val d = s.draft
    DetailScreen(onBack = onDone) {
        Text("What can $name do?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        // What this screen means, in one sentence; the controls follow at once.
        Note("Only you set or change what $name can do here, and it works one way: you get nothing on $name's phone.", icon = WMIcon.Lock)
        f.iAllowThem?.let { g ->
            val day = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
            fun fmt(iso: String?) = iso?.let { runCatching { day.format(Instant.parse(it)) }.getOrNull() }
            StatusPill(
                (if (g.paused) "Paused" else "Active") + " · version ${g.version} · until ${fmt(g.validUntil) ?: "—"}",
                if (g.paused) Tone.Warning else Tone.Info,
            )
            Body((fmt(g.lastUsedAt)?.let { "Last used $it." } ?: "$name hasn't used these yet.") +
                " Saved to your account and kept on this phone, so a change or removal applies here even when you're offline.")
        }
        FieldGroup("Start from") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                s.presets.filter { it.id != "focus_buddy" }.forEach { p ->
                    ChoiceChip(if (p.id == "gentle_nudges") "Gentle nudges" else "Wake buddy", selected = false, onClick = { vm.applyPreset(p) })
                }
            }
        }

        WMCard {
            SettingRow("Send short sounds", "A quack or chime, a few times an hour at most.", Capability.NUDGE in d.caps, icon = WMIcon.Quack, family = WM.palette.gold) { vm.toggle(Capability.NUDGE, it) }
            if (Capability.NUDGE in d.caps) {
                Stepper("Up to ${d.nudge.maxPerHour} per hour", d.nudge.maxPerHour, 1, 12) { v -> vm.edit { it.copy(nudge = it.nudge.copy(maxPerHour = v)) } }
                WindowEditor(d.nudge.windows.first()) { w -> vm.edit { it.copy(nudge = it.nudge.copy(windows = listOf(w))) } }
                SoundPicker(d.nudge.sounds, emptyList()) { list -> vm.edit { it.copy(nudge = it.nudge.copy(sounds = list)) } }
                SettingRow("Allow during my quiet hours", "Off means quacks wait for daytime. Turn on only if you want them at night too.", d.nudge.overridesQuietHours) { on -> vm.edit { it.copy(nudge = it.nudge.copy(overridesQuietHours = on)) } }
            }
        }

        WMCard {
            CardTitle("Help wake me")
            SettingRow("Set wake-up routines for me", "You'll see and accept every routine before it's active.", Capability.ALARM_SCHEDULE in d.caps, icon = WMIcon.CalendarPlus, family = WM.palette.sky) { vm.toggle(Capability.ALARM_SCHEDULE, it) }
            SettingRow("Wake me now", "Ring this phone during your wake hours, if you're not up.", Capability.ALARM_REQUEST in d.caps, icon = WMIcon.Wake, family = WM.palette.apricot) { vm.toggle(Capability.ALARM_REQUEST, it) }
            if (Capability.ALARM_REQUEST in d.caps || Capability.ALARM_SCHEDULE in d.caps) {
                Body("Wake hours are your explicit OK to be woken, even during quiet hours.")
                WindowEditor(d.alarm.windows.first()) { w -> vm.edit { it.copy(alarm = it.alarm.copy(windows = listOf(w))) } }
                Choice("Ring for at most", listOf(30, 60, 120, 300), d.alarm.maxAlarmSeconds, { "${it}s" }) { v -> vm.edit { it.copy(alarm = it.alarm.copy(maxAlarmSeconds = v)) } }
                Stepper("Up to ${d.alarm.maxPerDay} wake-up${if (d.alarm.maxPerDay > 1) "s" else ""} a day", d.alarm.maxPerDay, 1, 3) { v -> vm.edit { it.copy(alarm = it.alarm.copy(maxPerDay = v)) } }
                SoundPicker(d.alarm.sounds, customSounds) { list -> vm.edit { it.copy(alarm = it.alarm.copy(sounds = list)) } }
                Body("Approved voice recordings are available for wake alarms. Quacks use the built-in notification sounds.")
                SettingRow("Allow one follow-up alarm", "A second alarm a few minutes later unless you finish the challenge.", d.alarm.followUp.allowed) { on -> vm.edit { it.copy(alarm = it.alarm.copy(followUp = it.alarm.followUp.copy(allowed = on))) } }
            }
        }

        WMCard {
            SettingRow("Assign challenges", "Quizzes and puzzles to finish your wake-up. New versions always need your OK.", Capability.CHALLENGE_ASSIGN in d.caps, icon = WMIcon.Trophy, family = WM.palette.orchid) { vm.toggle(Capability.CHALLENGE_ASSIGN, it) }
            if (Capability.CHALLENGE_ASSIGN in d.caps) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                    CHALLENGE_TYPES.forEach { t ->
                        val on = t in d.challenge.types
                        ChoiceChip(t.replace('_', ' '), selected = on, onClick = { vm.edit { it.copy(challenge = it.challenge.copy(types = if (on) (it.challenge.types - t).ifEmpty { listOf("math") } else it.challenge.types + t)) } })
                    }
                }
                Choice("At most", listOf(30, 60, 90, 180), d.challenge.maxDurationSeconds, { "${it}s" }) { v -> vm.edit { it.copy(challenge = it.challenge.copy(maxDurationSeconds = v)) } }
                Choice("Hardest", listOf("easy", "medium", "hard"), d.challenge.maxDifficulty, { it }) { v -> vm.edit { it.copy(challenge = it.challenge.copy(maxDifficulty = v)) } }
            }
        }

        WMCard {
            CardTitle("Check in on me")
            SettingRow("Ask me to check in", "A request you can answer with “I'm OK”.", Capability.CHECKIN in d.caps, icon = WMIcon.CheckCircle, family = WM.palette.aqua) { vm.toggle(Capability.CHECKIN, it) }
            SettingRow("Ask where I am", "They can ask. Nothing is shared unless you choose to share, each time.", Capability.LOCATION in d.caps, icon = WMIcon.Pin, family = WM.palette.periwinkle) { vm.toggle(Capability.LOCATION, it) }
        }

        Choice("Keep these permissions for", listOf(30L, 90L, 180L), d.validDays, { "$it days" }) { v -> vm.edit { it.copy(validDays = v) } }
        PrimaryButton(if (s.saving) "Saving…" else "Save my rules", onClick = vm::save, enabled = !s.saving, icon = WMIcon.Check)
        if (f.iAllowThem != null) DangerButton("Remove all of $name's permissions", onClick = vm::revokeAll, icon = WMIcon.Trash)
        Gap(WakeMeTokens.spaceXl)
    }
    s.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit) {
    FieldGroup(label) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            options.forEach { o -> ChoiceChip(text(o), selected = o == selected, onClick = { onPick(o) }) }
        }
    }
}

@Composable
private fun SoundPicker(selected: List<String>, custom: List<String>, onChange: (List<String>) -> Unit) {
    FieldGroup("Sounds they can use") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            (BUNDLED + custom + selected).distinct().forEach { id ->
                val on = id in selected
                ChoiceChip(soundName(id), selected = on, onClick = { onChange(if (on) (selected - id).ifEmpty { listOf(id) } else selected + id) })
            }
        }
    }
}

/**
 * Seven day toggles in one row, Monday first; at least one stays selected. Each is a 48 dp-tall
 * target sharing the row's width (about 43 dp wide on a 360 dp phone), read as "Monday, checked".
 * A chosen day is lime with a bold forest letter, like every selection in Bloom; an open day keeps
 * an outline, so the state isn't colour alone.
 */
@Composable
fun DayChips(days: List<Int>, onChange: (List<Int>) -> Unit) {
    val names = listOf("M", "T", "W", "T", "F", "S", "S")
    val full = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val haptics = rememberHaptics()
    val c = WM.colors
    val off = quietFill()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
        ALL_DAYS.forEach { day ->
            val on = day in days
            val fill by animateColorAsState(if (on) c.accent else off, WMMotion.snappy(), label = "dayFill")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = WakeMeTokens.minTouchTarget)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = {
                        haptics.selection()
                        onChange(if (on) (days - day).ifEmpty { listOf(day) } else (days + day).sorted())
                    })
                    .semantics { contentDescription = full[day - 1] },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.sizeIn(maxWidth = 44.dp, maxHeight = 44.dp).aspectRatio(1f).fillMaxSize()
                        .background(fill, CircleShape)
                        .border(1.dp, if (on) Color.Transparent else c.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(names[day - 1], style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) c.textOnAccent else c.textPrimary)
                }
            }
        }
    }
}

/**
 * The routine's time as the biggest thing on screen, lime on the forest hero card (tabular, so it
 * doesn't shift while it changes), with an edit icon saying it can change. Tapping it opens the
 * system time picker; TalkBack reads "Rings at 07:00, change time".
 */
@Composable
fun TimeDisplay(label: String, time: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val c = WM.colors
    HeroCard(onClickLabel = "Change time", onClick = {
        val (h, m) = time.split(":").map { it.toInt() }
        TimePickerDialog(context, { _, hh, mm -> onPick("%02d:%02d".format(hh, mm)) }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = c.onHeroSecondary)
                AnimatedContent(targetState = time, transitionSpec = { WMMotion.fadeThrough() }, label = "time") { t ->
                    Text(t, style = TimeDisplayStyle, color = c.onHero)
                }
            }
            WMIconView(WMIcon.Edit, tint = c.onHero)
        }
    }
}

/**
 * A time of day ("HH:MM") as a compact outlined field: the role ("From") small above the time.
 * Opens the system time picker; read as "From 08:00, change time". Text stays "$label $time"
 * for the journey test via the merged description.
 */
@Composable
fun TimeOfDayButton(label: String, time: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val c = WM.colors
    val shape = RoundedCornerShape(WakeMeTokens.radiusMd)
    Column(
        modifier
            .heightIn(min = WakeMeTokens.buttonHeight)
            .clip(shape)
            .border(1.dp, c.outline, shape)
            .clickable(onClickLabel = "Change time", role = Role.Button) {
                val (h, m) = time.split(":").map { it.toInt() }
                TimePickerDialog(context, { _, hh, mm -> onPick("%02d:%02d".format(hh, mm)) }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
            }
            .padding(horizontal = WakeMeTokens.spaceMd, vertical = WakeMeTokens.spaceXs)
            .semantics(mergeDescendants = true) { contentDescription = "$label $time" },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Text(time, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
    }
}

@Composable
fun WindowEditor(window: LocalWindow, onChange: (LocalWindow) -> Unit) {
    DayChips(window.days) { onChange(window.copy(days = it)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm), verticalAlignment = Alignment.CenterVertically) {
        TimeOfDayButton("From", window.start, Modifier.weight(1f)) { s -> if (s != window.end) onChange(window.copy(start = s)) }
        TimeOfDayButton("Until", window.end, Modifier.weight(1f)) { e -> if (e != window.start) onChange(window.copy(end = e)) }
    }
    if (window.end <= window.start) Supporting("Ends the next morning.")
}

