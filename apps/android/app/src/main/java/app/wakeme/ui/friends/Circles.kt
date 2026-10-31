package app.wakeme.ui.friends

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import app.wakeme.designsystem.Loading
import app.wakeme.designsystem.FriendAvatar
import app.wakeme.designsystem.IconChip
import app.wakeme.designsystem.QuickAction
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMIconView
import app.wakeme.designsystem.WakeMeFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.CircleDetailDto
import app.wakeme.data.net.CircleDto
import app.wakeme.data.net.InviteCreatedDto
import app.wakeme.data.net.OfflineException
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DetailScreen
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.Supporting
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Private circles (doc 04 Stage 4): a group chat and shared challenges. Membership never grants phone permissions. */
data class CirclesState(
    val circles: List<CircleDto> = emptyList(),
    val detail: CircleDetailDto? = null,
    val myId: String? = null,
    val invite: InviteCreatedDto? = null,
    val message: String? = null,
    val left: Boolean = false,
)

@HiltViewModel
class CirclesViewModel @Inject constructor(private val api: WakeMeApi) : ViewModel() {
    private val _s = MutableStateFlow(CirclesState())
    val state: StateFlow<CirclesState> = _s

    fun loadList() = act { _s.value = _s.value.copy(circles = api.circles()) }

    fun load(id: String) = act { _s.value = _s.value.copy(detail = api.circle(id), myId = api.me().id) }

    fun create(name: String, onCreated: (String) -> Unit) = act {
        val c = api.createCircle(name.trim())
        _s.value = _s.value.copy(circles = api.circles())
        onCreated(c.id)
    }

    fun invite(id: String) = act { _s.value = _s.value.copy(invite = api.createCircleInvite(id)) }
    fun rename(id: String, name: String) = act { api.renameCircle(id, name.trim()); load(id) }
    fun remove(id: String, userId: String) = act { api.removeCircleMember(id, userId); load(id) }
    fun leave(id: String) = act { api.leaveCircle(id); _s.value = _s.value.copy(left = true) }
    fun dismiss() { _s.value = _s.value.copy(invite = null, message = null) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(message = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            _s.value = _s.value.copy(message = e.message)
        }
    }
}

/** The Circles part of the Friends tab: your circles, and making a new one. */
@Composable
fun CirclesSection(onOpen: (String) -> Unit, onTop: Boolean = true, vm: CirclesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // Also when the list is back on top beside a circle that was just left or renamed (two panes).
    LaunchedEffect(onTop) { if (onTop) vm.loadList() }
    CirclesList(s.circles, onOpen = onOpen, onNew = { naming = true })
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New circle") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Circle name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { naming = false; vm.create(name) { id -> name = ""; onOpen(id) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::dismiss, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
fun CircleScreen(circleId: String, onMessages: (String) -> Unit, onBack: () -> Unit, vm: CirclesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(circleId) { vm.load(circleId) }
    LaunchedEffect(s.left) { if (s.left) onBack() }
    val d = s.detail
    DetailScreen(onBack = onBack) {
        if (d == null) {
            Loading()
            return@DetailScreen
        }
        val admin = d.circle.role == "owner" || d.circle.role == "admin"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
            IconChip(WMIcon.Circles, circleFamily(d.circle.id), size = WakeMeTokens.avatarHeader)
            Text(d.circle.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        }
        // Quick actions: the words stay visible ("Invite" is read as "Invite to this circle").
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
            QuickAction(WMIcon.Message, "Messages", onClick = { onMessages(d.circle.conversationId) }, primary = true, modifier = Modifier.weight(1f))
            QuickAction(WMIcon.PersonPlus, "Invite", onClick = { vm.invite(circleId) }, family = WM.palette.sky, onClickLabel = "Invite to this circle", modifier = Modifier.weight(1f))
            if (admin) QuickAction(WMIcon.Edit, "Rename", onClick = { renaming = d.circle.name }, family = WM.palette.gold, onClickLabel = "Rename circle", modifier = Modifier.weight(1f))
        }
        SectionTitle("Members")
        // One card of member rows (avatar, name, role under it), like the friends list, rather than
        // a card and a pill per person.
        WMCard {
            d.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                    FriendAvatar(m.user.displayName, m.user.id)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.user.displayName + if (m.user.id == s.myId) " (you)" else "", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                        Supporting(
                            when {
                                m.role == "owner" -> "Owner"
                                m.role == "admin" -> "Admin"
                                m.user.id == s.myId -> "Member"
                                m.isFriend -> "Friend"
                                else -> "Not a friend"
                            },
                        )
                    }
                }
                if (admin && m.role != "owner" && m.user.id != s.myId) QuietButton("Remove ${m.user.displayName}", onClick = { vm.remove(circleId, m.user.id) }, icon = WMIcon.Close)
            }
        }
        DangerButton("Leave circle", onClick = { confirmLeave = true }, icon = WMIcon.Logout)
        Gap(WakeMeTokens.spaceXl)
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${d?.circle?.name ?: "this circle"}?") },
            text = { Text("You'll stop seeing its messages. Friendships and the permissions you chose stay as they are.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave(circleId) }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { current ->
        var name by remember(current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename circle") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Circle name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; vm.rename(circleId, name) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    s.invite?.let { inv ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Invite to ${d?.circle?.name ?: "the circle"}") },
            text = { InviteDialogBody(inv.code) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join ${d?.circle?.name ?: "our circle"} on Wake Me: ${inv.url} (code ${inv.code})"), "Share invite"))
                    vm.dismiss()
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Close") } },
        )
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::dismiss, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }, text = { Text(it) }) }
}

/** A circle's petal colour, stable for its id (the same family on every screen). */
@Composable
internal fun circleFamily(id: String): WakeMeFamily {
    val all = WM.palette.avatars
    return all[Math.floorMod(id.hashCode() + 3, all.size)]
}

/** Your circles without the ViewModel (the screenshot tests render it with sample data). */
@Composable
fun CirclesList(circles: List<CircleDto>, onOpen: (String) -> Unit, onNew: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        SectionTitle("Circles")
        circles.forEach { c ->
            // Same proportions as a friend row: a 17 sp title with its supporting line close below.
            WMCard(onClick = { onOpen(c.id) }, onClickLabel = "Open ${c.name}") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                    IconChip(WMIcon.Circles, circleFamily(c.id), size = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                        Supporting("${c.memberCount} member${if (c.memberCount == 1) "" else "s"}")
                    }
                    WMIconView(WMIcon.ChevronRight, tint = WM.colors.textSecondary, size = WakeMeTokens.iconSizeSmall)
                }
            }
        }
        QuietButton("New circle", onClick = onNew, icon = WMIcon.Plus)
    }
}
