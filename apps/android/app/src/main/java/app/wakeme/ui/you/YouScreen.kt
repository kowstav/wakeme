package app.wakeme.ui.you

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import app.wakeme.billing.Billing
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.BillingStatusDto
import app.wakeme.data.net.MeDto
import app.wakeme.data.net.OfflineException
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.BentoRow
import app.wakeme.designsystem.BentoTile
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.FriendAvatar
import app.wakeme.designsystem.IconChip
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMIconView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
import app.wakeme.designsystem.HeroCard
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.data.device.DeviceIdentity
import app.wakeme.data.net.DeviceDto
import app.wakeme.sync.DeviceRegistrar
import app.wakeme.sync.SyncEngine
import app.wakeme.ui.readiness.ReadinessSection
import app.wakeme.ui.AppViewModel
import app.wakeme.ui.paywall.billingStatusText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val HH_MM = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

data class YouState(
    val billing: BillingStatusDto? = null,
    val devices: List<DeviceDto> = emptyList(),
    val thisDeviceId: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val exportJson: String? = null,
)

@HiltViewModel
class YouViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WakeMeApi,
    private val billing: Billing,
    private val registrar: DeviceRegistrar,
    private val device: DeviceIdentity,
    private val sync: SyncEngine,
) : ViewModel() {
    private val _s = MutableStateFlow(YouState())
    val state: StateFlow<YouState> = _s

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { billing.status() }.onSuccess { _s.value = _s.value.copy(billing = it) }
            runCatching { api.devices() }.onSuccess { _s.value = _s.value.copy(devices = it, thisDeviceId = device.deviceId()) }
        }
    }

    /** Wake alarms ring on one primary phone; other signed-in devices get passive updates. */
    fun makeThisPhonePrimary() = act {
        registrar.makePrimary()
        runCatching { sync.sync() } // installs this phone's alarms now; the other phone removes its copy on its next sync
        _s.value = _s.value.copy(devices = api.devices(), thisDeviceId = device.deviceId())
    }

    fun update(body: JsonObject, onDone: (MeDto) -> Unit) = act { onDone(api.updateMe(body)) }

    fun export() = act {
        val json = api.exportAccount()
        _s.value = _s.value.copy(exportJson = WakeMeApiJson.encodeToString(JsonObject.serializer(), json))
    }

    fun writeExport(uri: Uri) = viewModelScope.launch {
        val json = _s.value.exportJson ?: return@launch
        val ok = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
        }
        _s.value = _s.value.copy(exportJson = null, notice = if (ok) "Your data was saved." else "Couldn't save the file.")
    }

    fun clearExport() {
        _s.value = _s.value.copy(exportJson = null)
    }

    fun deleteAccount(onDone: () -> Unit) = act {
        api.deleteAccount()
        onDone()
    }

    fun cancelDeletion(onDone: () -> Unit) = act {
        api.cancelDeletion()
        onDone()
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _s.value = _s.value.copy(busy = true)
        try {
            block()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(notice = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(
                notice = if (e.code == "reauth_required") "For your security, sign out and sign in again, then delete your account within a few minutes." else e.message,
            )
        }
        _s.value = _s.value.copy(busy = false)
    }

    fun clearNotice() {
        _s.value = _s.value.copy(notice = null)
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())
private fun whenText(iso: String?) = iso?.let { runCatching { dateFmt.format(Instant.parse(it)) }.getOrNull() }

/**
 * You: readiness, quiet hours, pause, account and subscription. Every control
 * here works without a subscription. Pausing and quiet hours are the
 * recipient's own consent settings and apply to every friend at once.
 */
@Composable
fun YouScreen(app: AppViewModel, onPaywall: () -> Unit, onSafety: () -> Unit, onFocus: () -> Unit = {}, onVoiceSounds: () -> Unit = {}, onReady: () -> Unit, vm: YouViewModel = hiltViewModel()) {
    val me by app.me.collectAsState()
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var editingQuiet by remember { mutableStateOf(false) }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.writeExport(uri) else vm.clearExport() }

    LifecycleResumeEffect(Unit) {
        vm.refresh() // billing and devices; ReadinessSection refreshes itself
        onPauseOrDispose { }
    }
    s.exportJson?.let {
        LaunchedEffect(it) { saveExport.launch("wakeme-export-${LocalDate.now()}.json") }
    }

    val palette = WM.palette
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = WakeMeTokens.screenMargin), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        item {
            Gap()
            ProfileOverview(
                name = me?.displayName ?: "You", personKey = me?.id ?: "me", timeZone = me?.timeZone ?: "",
                plus = s.billing?.plan == "plus" || me?.billing?.plan == "plus",
                onSafety = onSafety, onFocus = onFocus, onVoiceSounds = onVoiceSounds, onPaywall = onPaywall,
            )
        }

        me?.takeIf { it.accountState == "deletion_pending" }?.let { m ->
            item {
                WMCard {
                    StatusPill("Account scheduled for deletion" + (whenText(m.deletionScheduledFor)?.let { " on $it" } ?: ""), Tone.Danger, icon = WMIcon.Warning)
                    Body("Friends can't reach you, and the permissions you gave have been revoked. Cancelling keeps your account but doesn't restore those permissions.")
                    PrimaryButton("Keep my account", onClick = { vm.cancelDeletion { app.refreshMe() } })
                }
            }
        }

        item {
            Gap(WakeMeTokens.spaceSm)
            // One card for the whole checklist: each missing step is a row inside it.
            WMCard(onClick = onReady, onClickLabel = "Check alarm access") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(WMIcon.Phone, WM.palette.lime)
                    Text("Alarm access", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                    WMIconView(WMIcon.ChevronRight, tint = WM.colors.textSecondary)
                }
            }
        }
        item { PhonesSection(s.devices, s.thisDeviceId, busy = s.busy, onMakePrimary = vm::makeThisPhonePrimary) }

        // ------------------------------------------------------ consent settings
        me?.let { m ->
            item {
                SectionTitle("When friends can reach you")
                val q = m.settings.quietHours
                SettingRow(
                    "Quiet hours",
                    if (q.enabled) "${q.start}–${q.end}. Quacks arrive silently; only wake-ups you allowed for these hours can ring." else "Off",
                    q.enabled,
                    icon = WMIcon.Moon,
                    family = palette.periwinkle,
                ) { on -> vm.update(buildJsonObject { put("quietHours", buildJsonObject { put("enabled", on); put("start", q.start); put("end", q.end) }) }) { app.refreshMe() } }
                if (q.enabled) QuietButton("Change quiet hours", onClick = { editingQuiet = true }, icon = WMIcon.Edit)
            }
            item {
                val paused = m.settings.pausedUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it.isAfter(Instant.now()) }
                WMCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        IconChip(WMIcon.Hourglass, palette.sky)
                        Text("Pause all requests", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    if (paused != null) {
                        StatusPill("Paused until ${dateFmt.format(paused)}", Tone.Info, icon = WMIcon.Pause)
                        PrimaryButton("Resume now", onClick = { vm.update(buildJsonObject { put("pausedUntil", JsonNull) }) { app.refreshMe() } }, icon = WMIcon.Play)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                            listOf(1L to "1 hour", 8L to "8 hours", 24L to "1 day", 24L * 7 to "1 week").forEach { (h, label) ->
                                ChoiceChip(label, selected = false, onClick = { vm.update(buildJsonObject { put("pausedUntil", Instant.now().plusSeconds(h * 3600).toString()) }) { app.refreshMe() } })
                            }
                        }
                    }
                }
            }
            item {
                WMCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        IconChip(WMIcon.Speaker, palette.gold)
                        Text("Sounds per hour from friends", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Body("Silent after ${m.settings.soundBudgetPerHour} quacks an hour.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                        listOf(1, 3, 5, 10).forEach { n ->
                            ChoiceChip("$n", selected = m.settings.soundBudgetPerHour == n, onClick = { vm.update(buildJsonObject { put("soundBudgetPerHour", n) }) { app.refreshMe() } })
                        }
                    }
                }
            }
            item {
                SettingRow(
                    "Tell helpers when I skip a challenge",
                    "Off: helpers only see that the alarm stopped. On: they see that you ended it without the challenge.",
                    m.settings.shareBypass,
                    icon = WMIcon.Eye,
                    family = palette.sky,
                ) { on -> vm.update(buildJsonObject { put("shareBypass", on) }) { app.refreshMe() } }
            }
        }

        // ------------------------------------------------------- subscription
        item {
            SectionTitle("Subscription")
            val b = s.billing
            if (b != null) {
                val (text, tone) = billingStatusText(b)
                StatusPill(text, tone, icon = WMIcon.Sparkle)
                Body("AI credits: ${b.credits.available} available" + (whenText(b.credits.monthlyResetsAt)?.let { " · monthly credits refresh $it" } ?: ""))
            } else me?.let {
                Body("AI credits: ${it.billing.credits.available} available")
            }
            me?.let { Body("Routines ${it.limits.activeRoutines.used}/${it.limits.activeRoutines.max} · Custom sounds ${it.limits.customSounds.used}/${it.limits.customSounds.max}") }
            if (s.billing?.plan != "plus" && me?.billing?.plan != "plus") PrimaryButton("See Plus", onClick = onPaywall, icon = WMIcon.Sparkle)
            else QuietButton("Manage subscription", onClick = onPaywall, icon = WMIcon.ArrowUpRight)
        }

        // ------------------------------------------------------------ account
        item {
            SectionTitle("Account")
            SecondaryButton("Download my data", onClick = { vm.export() }, enabled = !s.busy, icon = WMIcon.Download)
            Gap(WakeMeTokens.spaceSm)
            SecondaryButton("Sign out", onClick = { app.signOut() }, icon = WMIcon.Logout)
            Gap(WakeMeTokens.spaceSm)
            if (me?.accountState != "deletion_pending") DangerButton("Delete my account", onClick = { confirmDelete = true }, icon = WMIcon.Trash)
            Gap(WakeMeTokens.spaceXl)
        }
    }

    if (editingQuiet) me?.let { m -> QuietHoursDialog(m, onDismiss = { editingQuiet = false }) { start, end ->
        editingQuiet = false
        vm.update(buildJsonObject { put("quietHours", buildJsonObject { put("enabled", true); put("start", start); put("end", end) }) }) { app.refreshMe() }
    } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("Your account is deactivated now and permanently deleted after the grace period. Every permission you gave friends is revoked straight away. Cancel a subscription separately in Google Play.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount { app.refreshMe() } }) { Text("Delete", color = WM.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep account") } },
        )
    }
    s.notice?.let { AlertDialog(onDismissRequest = vm::clearNotice, confirmButton = { TextButton(onClick = vm::clearNotice) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun PhonesSection(devices: List<DeviceDto>, thisDeviceId: String?, busy: Boolean, onMakePrimary: () -> Unit) {
    if (devices.isEmpty()) return
    val thisIsPrimary = devices.any { it.id == thisDeviceId && it.isPrimary }
    WMCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            IconChip(WMIcon.Phone, WM.palette.lime)
            Text("Phone for wake alarms", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.weight(1f))
        }
        devices.forEach { d ->
            val label = (if (d.id == thisDeviceId) "This phone" else d.platform.replaceFirstChar { it.uppercase() } + (d.osVersion?.let { " $it" } ?: "")) +
                (if (d.isPrimary) " · rings for wake alarms" else " · gets updates only")
            StatusPill(label, if (d.isPrimary) Tone.Success else Tone.Neutral, icon = if (d.isPrimary) WMIcon.Wake else WMIcon.Bell)
            if (d.readinessIssues.isNotEmpty() && d.id != thisDeviceId) Body("Needs setup.")
        }
        if (!thisIsPrimary) {
            Body("Only one phone rings.")
            SecondaryButton("Ring wake alarms on this phone", onClick = onMakePrimary, enabled = !busy, icon = WMIcon.Phone)
        }
    }
}

@Composable
private fun QuietHoursDialog(me: MeDto, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var start by remember { mutableStateOf(me.settings.quietHours.start) }
    var end by remember { mutableStateOf(me.settings.quietHours.end) }
    val valid = HH_MM.matches(start) && HH_MM.matches(end) && start != end
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet hours") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                OutlinedTextField(start, { start = it.take(5) }, label = { Text("Start (HH:MM)") }, singleLine = true, isError = !HH_MM.matches(start))
                OutlinedTextField(end, { end = it.take(5) }, label = { Text("End (HH:MM)") }, singleLine = true, isError = !HH_MM.matches(end))
                Body("In your time zone (${me.timeZone}).")
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(start, end) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
