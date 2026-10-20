package app.wakeme.ui.safety

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.FriendDto
import app.wakeme.data.net.LatestLocationDto
import app.wakeme.data.net.OfflineException
import app.wakeme.data.net.SafetySessionDto
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.HideFromRecents
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.SettingRow
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.safety.LocationShareService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** "Captured 12 minutes ago · accuracy about 80 m". Never presents an old sample as live. */
fun locationLabel(l: LatestLocationDto?): Pair<String, Tone> = when (l?.status) {
    null -> "Checking for a location…" to Tone.Neutral
    "not_shared" -> "Check-in only · no location shared" to Tone.Neutral
    "no_location" -> "No location available" to Tone.Warning
    else -> {
        val age = l.ageSeconds ?: 0
        val ago = when {
            age < 60 -> "less than a minute ago"
            age < 3600 -> "${age / 60} minute${if (age / 60 == 1) "" else "s"} ago"
            else -> "${age / 3600} h ${age % 3600 / 60} min ago"
        }
        val acc = l.accuracyMeters?.let { " · accuracy about $it m" } ?: ""
        ("Captured $ago$acc") to (if (age > 10 * 60) Tone.Warning else Tone.Info)
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
fun timeText(iso: String?): String = iso?.let { runCatching { clock.format(Instant.parse(it)) }.getOrNull() } ?: "—"

data class SafetyState(
    val loading: Boolean = true,
    val mine: List<SafetySessionDto> = emptyList(),
    val sharedWithMe: List<SafetySessionDto> = emptyList(),
    val friends: List<FriendDto> = emptyList(),
    val locations: Map<String, LatestLocationDto> = emptyMap(),
    val offline: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SafetyViewModel @Inject constructor(private val api: WakeMeApi) : ViewModel() {
    private val _s = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _s

    init {
        load()
        // Viewers refresh while the screen is open; the server keeps only the latest sample.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (_s.value.sharedWithMe.any { it.active }) refreshLocations()
            }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            val sessions = api.safetySessions()
            val friends = runCatching { api.friends() }.getOrDefault(_s.value.friends).filter { it.state == "accepted" }
            _s.value = _s.value.copy(loading = false, offline = false, mine = sessions.mine, sharedWithMe = sessions.sharedWithMe, friends = friends)
            refreshLocations()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(loading = false, offline = true)
        } catch (e: ApiException) {
            _s.value = _s.value.copy(loading = false, message = e.message)
        }
    }

    private suspend fun refreshLocations() {
        val out = _s.value.locations.toMutableMap()
        for (s in _s.value.sharedWithMe.filter { it.active }) {
            runCatching { api.latestLocation(s.id) }.onSuccess { out[s.id] = it }
        }
        _s.value = _s.value.copy(locations = out)
    }

    fun start(context: Context, purpose: String, viewers: List<FriendDto>, minutes: Int, shareLocation: Boolean, checkinInMinutes: Int?, message: String?) = act {
        val due = checkinInMinutes?.let { Instant.now().plusSeconds(it * 60L) }
        val s = api.startSafety(purpose, viewers.map { it.user.id }, minutes, shareLocation, due, message?.takeIf { it.isNotBlank() })
        if (s.shareLocation) LocationShareService.start(context, s.id, Instant.parse(s.endsAt), viewers.map { it.user.displayName })
    }

    fun sos(context: Context, viewers: List<FriendDto>, shareLocation: Boolean) = act {
        val s = api.sos(viewers.map { it.user.id }, shareLocation)
        if (s.shareLocation) LocationShareService.start(context, s.id, Instant.parse(s.endsAt), viewers.map { it.user.displayName })
    }

    /** Stop locally first so sharing ends at once even if the network is slow. */
    fun end(context: Context, s: SafetySessionDto) = act {
        if (LocationShareService.active.value?.sessionId == s.id) LocationShareService.stopLocal(context)
        api.endSafety(s.id)
    }

    fun checkIn(s: SafetySessionDto) = act { api.checkIn(s.id) }
    fun acknowledge(s: SafetySessionDto) = act { api.acknowledgeSafety(s.id) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _s.value = _s.value.copy(busy = true)
        try {
            block()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(message = "You're offline. Nothing was sent. Call or text your friends directly if you need help.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(message = e.message)
        }
        _s.value = _s.value.copy(busy = false)
        load()
    }

    fun clearMessage() {
        _s.value = _s.value.copy(message = null)
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Safety: owner-started, time-boxed check-ins and location shares with named
 * friends, plus SOS. Everything is visible to the sharer, expires on its own,
 * and can be stopped with one tap. Wake Me is not an emergency service.
 */
@Composable
fun SafetyScreen(focusSessionId: String? = null, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    HideFromRecents() // locations and who is told
    val sharing by LocationShareService.active.collectAsState()
    var composing by remember { mutableStateOf<String?>(null) } // "share" | "checkin" | "sos"
    LaunchedEffect(focusSessionId) { if (focusSessionId != null) vm.load() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = WakeMeTokens.spaceMd), contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        item {
            Gap()
            Text("Safety", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
            Body("Share with friends you choose, for a time you choose. Wake Me is not an emergency service: in an emergency, call your local emergency number.")
            if (s.offline) StatusPill("You're offline. Showing what was last loaded.", Tone.Warning)
        }
        sharing?.let { a ->
            item {
                WMCard {
                    StatusPill("Sharing your location now", Tone.Info)
                    Body("With ${a.viewerNames.joinToString(", ")} until ${clock.format(a.endsAt)}.")
                    Body(a.lastAccuracyMeters?.let { "Last sent ${a.lastSentAt?.let { t -> clock.format(t) } ?: ""} · accuracy about $it m" } ?: "Waiting for a location fix. Your friends see “No location available” until one arrives.")
                }
            }
        }
        // What friends share with you comes first, an SOS before anything else.
        val shared = s.sharedWithMe.filter { it.active }.sortedBy { if (it.purpose == "sos") 0 else 1 }
        if (shared.isNotEmpty()) item { SectionTitle("Shared with you") }
        items(shared, key = { "v" + it.id }) { v -> ViewerSession(v, s.locations[v.id], highlight = v.id == focusSessionId, onAck = { vm.acknowledge(v) }) }

        val active = s.mine.filter { it.active }
        if (active.isNotEmpty()) item { SectionTitle("You're sharing") }
        items(active, key = { "m" + it.id }) { m -> MySession(m, onCheckIn = { vm.checkIn(m) }, onEnd = { ctx -> vm.end(ctx, m) }) }
        if (active.isEmpty() && sharing == null) item { StatusPill("You're not sharing anything", Tone.Neutral) }

        item {
            Gap(WakeMeTokens.spaceSm)
            SectionTitle("Start")
            PrimaryButton("Check in with friends later", onClick = { composing = "checkin" }, enabled = !s.busy && s.friends.isNotEmpty())
            Gap(WakeMeTokens.spaceSm)
            SecondaryButton("Share my location for a while", onClick = { composing = "share" }, enabled = !s.busy && s.friends.isNotEmpty())
            if (s.friends.isEmpty() && !s.loading) Body("Add a friend first. Only friends you pick can see a session.")
        }

        item {
            Gap(WakeMeTokens.spaceLg)
            DangerButton("Send SOS to friends", onClick = { composing = "sos" })
            Body("Alerts the friends you pick, and shares your location for 2 hours if you allow it.")
            Gap(WakeMeTokens.spaceXl)
        }
    }

    composing?.let { kind ->
        StartSheet(kind, s.friends, onDismiss = { composing = null }, onStart = { purpose, viewers, minutes, shareLocation, checkin, message, ctx ->
            composing = null
            if (purpose == "sos") vm.sos(ctx, viewers, shareLocation) else vm.start(ctx, purpose, viewers, minutes, shareLocation, checkin, message)
        })
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun MySession(m: SafetySessionDto, onCheckIn: () -> Unit, onEnd: (Context) -> Unit) {
    val context = LocalContext.current
    WMCard {
        Text(
            when (m.purpose) { "sos" -> "SOS"; "checkin" -> "Check-in"; else -> "Location share" },
            style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary,
        )
        val viewers = m.viewers.filter { !it.revoked }
        Body("Visible to ${viewers.joinToString(", ") { it.displayName }.ifBlank { "nobody (all viewers removed)" }} until ${timeText(m.endsAt)}.")
        if (m.viewers.any { it.revoked }) Body("No longer visible to ${m.viewers.filter { it.revoked }.joinToString(", ") { it.displayName }}.")
        StatusPill(if (m.shareLocation) "Location: shared" else "Location: not shared", if (m.shareLocation) Tone.Info else Tone.Neutral)
        if (m.purpose == "sos") {
            val acked = m.viewers.filter { it.acknowledgedAt != null }
            StatusPill(if (acked.isEmpty()) "Sent · nobody has confirmed seeing it yet" else "Seen by ${acked.joinToString(", ") { it.displayName }}", if (acked.isEmpty()) Tone.Warning else Tone.Success)
        }
        if (m.purpose == "checkin") {
            if (m.checkedInAt != null) StatusPill("Checked in at ${timeText(m.checkedInAt)}", Tone.Success)
            else {
                Body("If you don't check in by ${timeText(m.checkinDueAt)}, they'll get a notice that you haven't checked in yet. That's all: nobody else is contacted.")
                PrimaryButton("I'm OK — check in now", onClick = onCheckIn)
            }
        }
        DangerButton("Stop now", onClick = { onEnd(context) })
    }
}

@Composable
private fun ViewerSession(v: SafetySessionDto, loc: LatestLocationDto?, highlight: Boolean, onAck: () -> Unit) {
    val context = LocalContext.current
    WMCard {
        Text(
            when (v.purpose) { "sos" -> "SOS from ${v.owner.displayName}"; "checkin" -> "${v.owner.displayName}'s check-in"; else -> "${v.owner.displayName} is sharing location" },
            style = MaterialTheme.typography.titleLarge, fontWeight = if (highlight || v.purpose == "sos") FontWeight.Bold else FontWeight.Normal, color = WM.colors.textPrimary,
        )
        v.message?.let { Body("“$it”") }
        Body("Until ${timeText(v.endsAt)}")
        if (v.purpose == "checkin") {
            StatusPill(
                if (v.checkedInAt != null) "Checked in at ${timeText(v.checkedInAt)}" else "Check-in due at ${timeText(v.checkinDueAt)}",
                if (v.checkedInAt != null) Tone.Success else Tone.Neutral,
            )
        }
        val (label, tone) = locationLabel(if (v.shareLocation) loc else LatestLocationDto(status = "not_shared"))
        StatusPill(label, tone)
        if (loc?.status == "available" && loc.latitude != null && loc.longitude != null) {
            val captured = timeText(loc.capturedAt)
            SecondaryButton("Open in maps (position at $captured)", onClick = {
                val uri = Uri.parse("geo:0,0?q=${loc.latitude},${loc.longitude}(${Uri.encode(v.owner.displayName + " at " + captured)})")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            })
        }
        if (v.purpose == "sos") {
            Body("Try calling ${v.owner.displayName}. If you think they're in danger, call your local emergency number.")
            // A viewer's list holds only themselves; their acknowledgement is shown back to them.
            val seen = v.viewers.firstOrNull()?.acknowledgedAt
            if (seen != null) StatusPill("${v.owner.displayName} knows you've seen this (${timeText(seen)})", Tone.Success)
            else PrimaryButton("I've seen this", onClick = onAck)
        }
    }
}

@Composable
private fun StartSheet(
    kind: String,
    friends: List<FriendDto>,
    onDismiss: () -> Unit,
    onStart: (purpose: String, viewers: List<FriendDto>, minutes: Int, shareLocation: Boolean, checkinInMinutes: Int?, message: String?, context: Context) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(setOf<String>()) }
    var minutes by remember { mutableStateOf(if (kind == "checkin") 120 else 60) }
    var checkin by remember { mutableStateOf(60) }
    var shareLocation by remember { mutableStateOf(kind != "checkin") }
    var message by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }
    /** An SOS gets location through the system location button (Android 17; older versions show the usual prompt). */
    var buttonGranted by remember { mutableStateOf(false) }
    val viewers = friends.filter { it.friendshipId in selected }
    val go = { onStart(kind, viewers, minutes, shareLocation && (hasLocationPermission(context) || buttonGranted), if (kind == "checkin") checkin else null, message, context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) go() else permissionDenied = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (kind) { "sos" -> "Send SOS"; "checkin" -> "Check in later"; else -> "Share my location" }) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                item { Body("Who can see it?") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        friends.take(10).forEach { f ->
                            FilterChip(selected = f.friendshipId in selected, onClick = { selected = if (f.friendshipId in selected) selected - f.friendshipId else selected + f.friendshipId }, label = { Text(f.user.displayName) })
                        }
                    }
                }
                // Who is told and until when, right under the choice of friends: the dialog scrolls,
                // and this line must not end up below the fold.
                item {
                    val until = clock.format(Instant.now().plus(Duration.ofMinutes(if (kind == "sos") 120L else minutes.toLong())))
                    Body(
                        if (viewers.isEmpty()) "Pick at least one friend."
                        else "${viewers.joinToString(", ") { it.user.displayName }} will be notified and can see this until $until. You can stop it anytime.",
                        color = WM.colors.textPrimary,
                    )
                }
                if (kind != "sos") {
                    item { Body("For how long?") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                            listOf(30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 480 to "8 hours").forEach { (m, label) ->
                                FilterChip(selected = minutes == m, onClick = { minutes = m; if (checkin > m) checkin = m }, label = { Text(label) })
                            }
                        }
                    }
                }
                if (kind == "checkin") {
                    item { Body("Check in within") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                            listOf(15, 30, 60, 120, 240).filter { it <= minutes }.forEach { m ->
                                FilterChip(selected = checkin == m, onClick = { checkin = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                            }
                        }
                    }
                }
                item { SettingRow("Share my location", "Friends see where you were, when it was captured and how accurate it is. You'll see a notification with Stop while it's on.", shareLocation) { shareLocation = it } }
                if (kind != "sos") item { OutlinedTextField(message, { if (it.length <= 200) message = it }, label = { Text("Note (optional)") }, singleLine = true) }
                // Doc 06: "share my location now" for an SOS uses the session-scoped system button, separate
                // from timed shares. Without it the SOS still goes, with no location.
                if (kind == "sos" && shareLocation && !hasLocationPermission(context) && !buttonGranted) {
                    item { Body("Tap to include where you are. Without it, friends get the SOS with no location.") }
                    item { SystemLocationButton(onResult = { granted -> buttonGranted = granted; permissionDenied = !granted }) }
                }
                if (buttonGranted) item { StatusPill("Your location will be included.", Tone.Info) }
                if (permissionDenied) {
                    item { StatusPill("Location is off for Wake Me, so only the alert is sent, with no location.", Tone.Warning) }
                    // Android doesn't show the prompt again after a permanent denial: offer the app's settings.
                    item {
                        QuietButton("Open Wake Me settings", onClick = {
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) }
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = viewers.isNotEmpty(), onClick = {
                if (kind != "sos" && shareLocation && !hasLocationPermission(context) && !permissionDenied) {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else go()
            }) { Text(if (kind == "sos") "Send SOS" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Android 17's system-rendered location button (doc 06): a tap grants precise location for this
 * session only. On Android 16 and earlier the library shows a look-alike that opens the usual
 * permission prompt. Hosted as a View: the Compose artifact would pull alpha Material3 (see
 * libs.versions.toml). Not yet exercised on an emulator or phone.
 */
@Composable
private fun SystemLocationButton(onResult: (Boolean) -> Unit) {
    val activity = LocalContext.current.let { c ->
        generateSequence(c) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity
    } ?: return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.core.locationbutton.LocationButton(ctx).apply {
                parentActivity = activity
                setTextType(androidx.core.locationbutton.LocationButton.TEXT_TYPE_SHARE_PRECISE_LOCATION)
                setOnPermissionResultListener(object : androidx.core.locationbutton.OnPermissionResultListener {
                    override fun onPermissionResult(granted: Boolean) = onResult(granted)
                })
                setOnErrorListener(object : androidx.core.locationbutton.OnErrorListener {
                    override fun onError(error: Throwable) = onResult(false)
                })
            }
        },
    )
}
