package app.wakeme.ui.friends

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wakeme.core.Capability
import app.wakeme.data.net.FriendAction
import app.wakeme.data.net.FriendDto
import app.wakeme.designsystem.AdaptiveList
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.CardTitle
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.DeliveryTrailView
import app.wakeme.designsystem.DetailTopBar
import app.wakeme.designsystem.EmptyState
import app.wakeme.designsystem.FriendAvatar
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuackBurst
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.ReloadWhenBackOnTop
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WMMotion
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.designsystem.fullWidth
import app.wakeme.designsystem.rememberHaptics
import app.wakeme.readiness.ReadinessItem
import app.wakeme.ui.onboarding.playQuackPreview
import app.wakeme.ui.permissions.notificationsAllowed
import app.wakeme.ui.permissions.notificationsAsked
import app.wakeme.ui.permissions.rememberNotificationAsk
import app.wakeme.ui.soundName

@Composable
fun FriendsScreen(onOpen: (String) -> Unit, onOpenCircle: (String) -> Unit = {}, onTop: Boolean = true, vm: FriendsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val askNotifications = rememberNotificationAsk()
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsAllowed(context)
        vm.refreshReadiness()
        onPauseOrDispose { }
    }
    ReloadWhenBackOnTop(onTop, vm::load)
    FriendsList(
        s = s,
        notificationsOn = notificationsOn,
        onOpen = onOpen,
        onFixReadiness = { issue -> context.startActivity(vm.settingsIntent(issue)) },
        onTurnOnNotifications = {
            if (notificationsAsked(context)) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } else {
                askNotifications("So quacks and requests from your friends can reach you.") { notificationsOn = notificationsAllowed(context) }
            }
        },
        onInvite = vm::createInvite,
        onRedeem = vm::redeemCode,
        circles = { CirclesSection(onOpen = onOpenCircle, onTop = onTop) },
    )
    s.invite?.let { inv ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("Share your invite") },
            text = { Text("Link: ${inv.url}\n\nIf the link doesn't open the app, they can type this code: ${inv.code}\n\nIt works once and expires soon. Accepting makes you friends and nothing more: each of you chooses what the other can do.") },
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

/** The Friends tab's content without its ViewModel (the screenshot tests render it with sample data). */
@Composable
fun FriendsList(
    s: FriendsState,
    notificationsOn: Boolean,
    onOpen: (String) -> Unit,
    onFixReadiness: (ReadinessItem) -> Unit,
    onTurnOnNotifications: () -> Unit,
    onInvite: () -> Unit,
    onRedeem: (String) -> Unit,
    circles: @Composable () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AdaptiveList(Modifier.padding(horizontal = WakeMeTokens.spaceMd), contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()) {
        fullWidth {
            Gap()
            Text("Friends", style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
            if (s.offline) StatusPill("Offline · showing nothing new. Your alarms still work.", Tone.Warning, announce = true)
        }
        // Doc 03 §3: home shows the next alarm on this phone and, at most, one readiness issue.
        s.nextAlarm?.let { next ->
            fullWidth {
                WMCard {
                    Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.layoutTitleGap)) {
                        Text("Next alarm on this phone", style = MaterialTheme.typography.labelLarge, color = WM.colors.textSecondary)
                        Text(nextAlarmText(next.fireAt, next.label), style = MaterialTheme.typography.headlineSmall, color = WM.colors.textPrimary)
                    }
                    s.readinessIssue?.let { issue ->
                        StatusPill(readinessIssueText(issue), Tone.Warning)
                        SecondaryButton(readinessFixLabel(issue), onClick = { onFixReadiness(issue) })
                    }
                }
            }
        }
        if (!s.loading && s.friends.isEmpty()) {
            fullWidth {
                EmptyState(
                    title = "Invite someone you trust",
                    body = "Send a private link. They choose what you can do on their phone, and you choose what they can do on yours.",
                )
            }
        }
        if (s.friends.isNotEmpty() && !notificationsOn) {
            fullWidth {
                WMCard {
                    Body("Notifications are off, so quacks from friends can't reach you.", color = WM.colors.textPrimary)
                    SecondaryButton("Turn on notifications", onClick = onTurnOnNotifications)
                }
            }
        }
        items(s.friends, key = { it.friendshipId }) { f -> FriendCard(f, Modifier.animateItem(), onOpen = { onOpen(f.friendshipId) }) }
        fullWidth { Gap(); circles() }
        fullWidth {
            Gap()
            PrimaryButton("Invite a friend", onClick = onInvite)
            Gap(WakeMeTokens.spaceSm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.uppercase().take(11) }, label = { Text("Have a code?") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRedeem(code); code = "" }, enabled = code.length >= 9) { Text("Join") }
            }
            Gap(WakeMeTokens.spaceXl)
        }
    }
}

/** A friend: who, what you can do on their phone, and whether a quack is possible right now. */
@Composable
private fun FriendCard(f: FriendDto, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val quack = f.actions.firstOrNull { it.capability == Capability.NUDGE }
    val name = f.user.displayName
    WMCard(modifier, onClick = onOpen, onClickLabel = "Open $name") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            FriendAvatar(name, f.user.id, size = WakeMeTokens.avatarRow)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                val allowed = f.theyAllowMe?.capabilities.orEmpty()
                Text(
                    if (allowed.isEmpty()) "$name hasn't allowed anything yet." else "You can " + wordList(allowed.map(::capabilityLabelForThem)) + ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WM.colors.textSecondary,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = WM.colors.textSecondary)
        }
        if (quack?.availableNow == true) StatusPill("Quack available now", Tone.Success)
        else if (quack?.granted == true) StatusPill(reasonText(name, quack.reason), Tone.Neutral)
    }
}

/** What a friend may do on your phone, as words after "Alex can" (same wording as iOS). */
fun capabilityLabel(c: String) = when (c) {
    Capability.NUDGE -> "send quacks"
    Capability.ALARM_REQUEST -> "wake you now"
    Capability.ALARM_SCHEDULE -> "suggest wake-up routines"
    Capability.CHALLENGE_ASSIGN -> "assign challenges"
    Capability.FOCUS -> "ask you to focus"
    Capability.CHECKIN -> "ask you to check in"
    Capability.LOCATION -> "ask where you are"
    else -> c
}

/** What you may do on a friend's phone, as words after "You can". */
fun capabilityLabelForThem(c: String) = when (c) {
    Capability.NUDGE -> "send quacks"
    Capability.ALARM_REQUEST -> "wake them now"
    Capability.ALARM_SCHEDULE -> "suggest wake-up routines"
    Capability.CHALLENGE_ASSIGN -> "assign challenges"
    Capability.FOCUS -> "ask them to focus"
    Capability.CHECKIN -> "ask them to check in"
    Capability.LOCATION -> "ask where they are"
    else -> c
}

/** "a, b and c" */
fun wordList(items: List<String>): String = if (items.size < 2) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()

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
    // Doc 03 §2: a subtle confirmation on send, a distinct but calm warning on failure; the
    // system haptics setting decides whether either is felt.
    val haptics = rememberHaptics()
    LaunchedEffect(s.sentTick) { if (s.sentTick > 0) haptics.sent() }
    LaunchedEffect(s.failTick) { if (s.failTick > 0) haptics.failed() }
    val quack = f.actions.firstOrNull { it.capability == Capability.NUDGE }
    val quackSent = s.lastSent?.capability == Capability.NUDGE && s.sentTick > 0

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        DetailTopBar(onBack = onBack, modifier = Modifier.padding(horizontal = WakeMeTokens.spaceXxs))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WakeMeTokens.spaceMd).padding(bottom = WakeMeTokens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
                FriendAvatar(name, f.user.id, size = WakeMeTokens.avatarHeader)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
                    Text(name, style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
                    if (quack?.availableNow == true) StatusPill("Quack available now", Tone.Success)
                }
            }

            // ---- Send a quack: preview (local only) is a different control from Send.
            WMCard {
                // The duck's canvas already reserves room for its sound marks, so the title sits close.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
                    // Replays only after a send the server accepted; says nothing about delivery.
                    QuackBurst(tick = if (quackSent) s.sentTick else 0, height = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CardTitle("Quack")
                        val sounds = f.theyAllowMe?.limits?.nudge?.sounds.orEmpty()
                        if (sounds.isNotEmpty()) Supporting("Only sounds $name allowed.")
                    }
                }
                val sounds = f.theyAllowMe?.limits?.nudge?.sounds.orEmpty()
                if (sounds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                        sounds.take(4).forEach { id -> ChoiceChip(soundName(id), selected = sound == id, onClick = { sound = id }) }
                    }
                    SecondaryButton("Preview on my phone", onClick = { playQuackPreview(context) })
                }
                ActionButton("Send quack", quack, name, s.sending == Capability.NUDGE) {
                    askNotifications("So you can see when $name replies, and hear quacks you've allowed.") {
                        vm.send(Capability.NUDGE, mapOf("soundId" to (sound ?: "bundled:quack.classic")))
                    }
                }
                AnimatedVisibility(visible = quackSent, enter = fadeIn(WMMotion.standard()), exit = fadeOut(WMMotion.quick())) {
                    Text("Sent to $name", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                }
            }

            // ---- Escalation only if they enabled it; never automatic.
            val wake = f.actions.firstOrNull { it.capability == Capability.ALARM_REQUEST }
            if (wake?.granted == true) {
                WMCard {
                    CardTitle("Wake alarm")
                    Body("Rings their phone for up to ${f.theyAllowMe?.limits?.alarm?.maxAlarmSeconds ?: 60} seconds, inside the wake hours they chose.")
                    ActionButton("Send wake alarm", wake, name, s.sending == Capability.ALARM_REQUEST) {
                        vm.send(Capability.ALARM_REQUEST, mapOf("soundId" to (f.theyAllowMe?.limits?.alarm?.sounds?.firstOrNull() ?: "bundled:rise.gentle"), "ringSeconds" to minOf(60, f.theyAllowMe?.limits?.alarm?.maxAlarmSeconds ?: 60)))
                    }
                }
            } else {
                Body("$name hasn't enabled wake alarms for you. You can ask them in a message.")
            }

            // ---- What the server knows about the last request, in words, then as a trail.
            s.lastSent?.let { c ->
                WMCard {
                    CardTitle("Last request")
                    StatusPill(statusText(context, c.displayStatus), when (c.displayStatus) {
                        "acknowledged", "challenge_completed" -> Tone.Success
                        "not_delivered", "expired", "not_played" -> Tone.Warning
                        else -> Tone.Info
                    }, announce = true)
                    if (c.capability == Capability.NUDGE || c.capability == Capability.ALARM_REQUEST) {
                        DeliveryTrailView(c.displayStatus, alarm = c.capability == Capability.ALARM_REQUEST)
                    }
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
                CardTitle("What $name can do on your phone")
                val mine = f.iAllowThem
                Body(if (mine == null) "Nothing yet. Being friends doesn't allow anything by itself." else "$name can " + wordList(mine.capabilities.map(::capabilityLabel)) + "." + if (mine.paused) " Paused for now." else "")
                if (mine == null) PrimaryButton("Choose what $name can do", onClick = { onEditPermissions(friendshipId) })
                else SecondaryButton("Change permissions", onClick = { onEditPermissions(friendshipId) })
                mine?.let { QuietButton(if (it.paused) "Resume $name's permissions" else "Pause $name's permissions", onClick = { vm.pause(!it.paused) }) }
                Supporting("Circles you share don't change these settings.")
            }
            SecondaryButton("Safety check-in or SOS", onClick = onSafety)
            QuietButton("Remove friend", onClick = { vm.remove(onBack) })
            DangerButton("Block and report", onClick = { confirmBlock = true })
            Gap(WakeMeTokens.spaceLg)
        }
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

/** "Today 07:00 · Gym", "Tomorrow 06:30", "Tue 07:00" in this phone's time zone. */
internal fun nextAlarmText(fireAtMillis: Long, label: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
    val at = java.time.Instant.ofEpochMilli(fireAtMillis).atZone(now.zone)
    val time = at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val day = when (java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault()))
    }
    return "$day $time" + if (label.isNotBlank()) " · $label" else ""
}

private fun readinessIssueText(item: ReadinessItem) = when (item) {
    ReadinessItem.EXACT_ALARMS -> "Exact alarms are off, so it may not ring on time."
    ReadinessItem.ALARM_VOLUME -> "Alarm volume is at zero, so it would be silent."
    ReadinessItem.FULL_SCREEN -> "It can't open over the lock screen."
    ReadinessItem.NOTIFICATIONS -> "Notifications are off."
    ReadinessItem.BATTERY -> "Battery optimisation may delay it."
}

private fun readinessFixLabel(item: ReadinessItem) = when (item) {
    ReadinessItem.EXACT_ALARMS -> "Allow exact alarms"
    ReadinessItem.ALARM_VOLUME -> "Open sound settings"
    ReadinessItem.FULL_SCREEN -> "Allow full-screen alarms"
    ReadinessItem.NOTIFICATIONS -> "Turn on notifications"
    ReadinessItem.BATTERY -> "Open battery settings"
}
