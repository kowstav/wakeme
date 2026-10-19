package app.wakeme.ui.friends

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wakeme.ui.permissions.notificationsAllowed
import app.wakeme.ui.permissions.notificationsAsked
import app.wakeme.ui.permissions.rememberNotificationAsk
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import app.wakeme.core.Capability
import app.wakeme.data.net.FriendAction
import app.wakeme.data.net.FriendDto
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
import app.wakeme.ui.onboarding.playQuackPreview

@Composable
fun FriendsScreen(onOpen: (String) -> Unit, vm: FriendsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    val askNotifications = rememberNotificationAsk()
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsAllowed(context)
        onPauseOrDispose { }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        item {
            Gap()
            Text("Friends", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            if (s.offline) StatusPill("Offline · showing nothing new. Your alarms still work.", Tone.Warning)
        }
        if (!s.loading && s.friends.isEmpty()) {
            item {
                WMCard {
                    Text("Invite someone you trust", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                    Body("Send a private link. They choose what you can do on their phone, and you choose what they can do on yours.")
                }
            }
        }
        if (s.friends.isNotEmpty() && !notificationsOn) {
            item {
                WMCard {
                    Body("Notifications are off, so quacks from friends can't reach you.", color = WM.colors.textPrimary)
                    SecondaryButton("Turn on notifications", onClick = {
                        if (notificationsAsked(context)) {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        } else {
                            askNotifications("So quacks and requests from your friends can reach you.") { notificationsOn = notificationsAllowed(context) }
                        }
                    })
                }
            }
        }
        items(s.friends, key = { it.friendshipId }) { f -> FriendCard(f, onOpen = { onOpen(f.friendshipId) }) }
        item {
            Gap()
            PrimaryButton("Invite a friend", onClick = vm::createInvite)
            Gap(WakeMeTokens.spaceSm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.uppercase().take(11) }, label = { Text("Have a code?") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.redeemCode(code); code = "" }, enabled = code.length >= 9) { Text("Join") }
            }
            Gap(WakeMeTokens.spaceXl)
        }
    }
    s.invite?.let { inv ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("Share your invite") },
            text = { Text("Link: ${inv.url}\n\nIf the link doesn't open the app, they can type this code: ${inv.code}\n\nIt works once and expires soon. Opening it gives you no permissions.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Let's wake each other up on Wake Me: ${inv.url} (code ${inv.code})"), "Share invite"))
                    vm.dismissMessage()
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = vm::dismissMessage) { Text("Close") } },
        )
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::dismissMessage, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun FriendCard(f: FriendDto, onOpen: () -> Unit) {
    val quack = f.actions.firstOrNull { it.capability == Capability.NUDGE }
    WMCard(Modifier.clickable(onClick = onOpen)) {
        Text(f.user.displayName, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
        val allowed = f.theyAllowMe?.capabilities.orEmpty()
        Body(if (allowed.isEmpty()) "${f.user.displayName} hasn't allowed anything yet." else "You can: " + allowed.joinToString(", ") { capabilityLabel(it) })
        if (quack?.availableNow == true) StatusPill("Quack available now", Tone.Success)
        else if (quack?.granted == true) StatusPill(reasonText(f.user.displayName, quack.reason), Tone.Neutral)
    }
}

fun capabilityLabel(c: String) = when (c) {
    Capability.NUDGE -> "quack"
    Capability.ALARM_REQUEST -> "wake now"
    Capability.ALARM_SCHEDULE -> "set wake-ups"
    Capability.CHALLENGE_ASSIGN -> "assign challenges"
    Capability.FOCUS -> "ask to focus"
    Capability.CHECKIN -> "check in"
    Capability.LOCATION -> "ask where you are"
    else -> c
}

@Composable
fun FriendDetailScreen(
    friendshipId: String,
    onEditPermissions: (String) -> Unit,
    onMessages: (String) -> Unit,
    onProposeRoutine: (String) -> Unit,
    onSafety: () -> Unit,
    onBack: () -> Unit,
    vm: FriendDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(friendshipId) { vm.load(friendshipId) }
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val f = s.friend ?: return
    var sound by remember(f.theyAllowMe?.version) { mutableStateOf(f.theyAllowMe?.limits?.nudge?.sounds?.firstOrNull()) }
    var confirmBlock by remember { mutableStateOf(false) }
    val name = f.user.displayName
    val askNotifications = rememberNotificationAsk()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)

        // ---- Send a quack: preview (local only) is a different control from Send.
        val quack = f.actions.firstOrNull { it.capability == Capability.NUDGE }
        WMCard {
            SectionTitle("Quack")
            val sounds = f.theyAllowMe?.limits?.nudge?.sounds.orEmpty()
            if (sounds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                    sounds.take(4).forEach { id -> FilterChip(selected = sound == id, onClick = { sound = id }, label = { Text(id.removePrefix("bundled:").replace('.', ' ')) }) }
                }
                QuietButton("Preview on my phone", onClick = { playQuackPreview(context) })
            }
            ActionButton("Send quack", quack, name, s.sending == Capability.NUDGE) {
                askNotifications("So you can see when $name replies, and hear quacks you've allowed.") {
                    vm.send(Capability.NUDGE, mapOf("soundId" to (sound ?: "bundled:quack.classic")))
                }
            }
        }

        // ---- Escalation only if they enabled it; never automatic.
        val wake = f.actions.firstOrNull { it.capability == Capability.ALARM_REQUEST }
        if (wake?.granted == true) {
            WMCard {
                SectionTitle("Wake alarm")
                Body("Rings their phone for up to ${f.theyAllowMe?.limits?.alarm?.maxAlarmSeconds ?: 60} seconds, inside the wake hours they chose.")
                ActionButton("Send wake alarm", wake, name, s.sending == Capability.ALARM_REQUEST) {
                    vm.send(Capability.ALARM_REQUEST, mapOf("soundId" to (f.theyAllowMe?.limits?.alarm?.sounds?.firstOrNull() ?: "bundled:rise.gentle"), "ringSeconds" to minOf(60, f.theyAllowMe?.limits?.alarm?.maxAlarmSeconds ?: 60)))
                }
            }
        } else {
            Body("$name hasn't enabled wake alarms for you. You can ask them in a message.")
        }

        s.lastSent?.let { c ->
            WMCard {
                Text("Last request", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                StatusPill(statusText(context, c.displayStatus), when (c.displayStatus) {
                    "acknowledged", "challenge_completed" -> Tone.Success
                    "not_delivered", "expired", "not_played" -> Tone.Warning
                    else -> Tone.Info
                })
            }
        }

        val checkin = f.actions.firstOrNull { it.capability == Capability.CHECKIN }
        if (checkin?.granted == true) SecondaryButton("Ask $name to check in", onClick = { vm.send(Capability.CHECKIN, emptyMap()) }, enabled = checkin.availableNow)
        val location = f.actions.firstOrNull { it.capability == Capability.LOCATION }
        if (location?.granted == true) SecondaryButton("Ask where $name is", onClick = { vm.send(Capability.LOCATION, emptyMap()) }, enabled = location.availableNow)
        if (f.theyAllowMe?.capabilities?.contains(Capability.ALARM_SCHEDULE) == true) SecondaryButton("Suggest a wake-up routine", onClick = { onProposeRoutine(f.user.id) })
        f.conversationId?.let { SecondaryButton("Messages", onClick = { onMessages(it) }) }

        // ---- What THEY can do on MY phone: a separate, recipient-owned decision.
        WMCard {
            SectionTitle("What $name can do on your phone")
            val mine = f.iAllowThem
            Body(if (mine == null) "Nothing yet. Being friends doesn't allow anything by itself." else mine.capabilities.joinToString(", ") { capabilityLabel(it) } + if (mine.paused) " (paused)" else "")
            PrimaryButton(if (mine == null) "Choose what $name can do" else "Change permissions", onClick = { onEditPermissions(friendshipId) })
            mine?.let { QuietButton(if (it.paused) "Resume $name's permissions" else "Pause $name's permissions", onClick = { vm.pause(!it.paused) }) }
            Body("Circles you share don't change these settings.")
        }
        SecondaryButton("Safety check-in or SOS", onClick = onSafety)
        QuietButton("Remove friend", onClick = { vm.remove(onBack) })
        DangerButton("Block and report", onClick = { confirmBlock = true })
        Gap(WakeMeTokens.spaceXl)
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Block $name?") },
            text = { Text("This immediately removes every permission between you, cancels their pending requests and routines for you, and ends any location sharing. They aren't told.") },
            confirmButton = { TextButton(onClick = { confirmBlock = false; vm.block("unwanted_alerts", onBack) }) { Text("Block and report") } },
            dismissButton = { TextButton(onClick = { confirmBlock = false; vm.block(null, onBack) }) { Text("Block only") } },
        )
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::dismissMessage, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun ActionButton(label: String, action: FriendAction?, name: String, busy: Boolean, onClick: () -> Unit) {
    if (action?.availableNow == true) {
        PrimaryButton(if (busy) "Sending…" else label, onClick = onClick, enabled = !busy)
    } else {
        SecondaryButton(label, onClick = {}, enabled = false)
        Body(reasonText(name, action?.reason ?: "capability_not_granted"))
    }
}
