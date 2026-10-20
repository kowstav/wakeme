package app.wakeme.ui.friends

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.CircleDetailDto
import app.wakeme.data.net.CircleDto
import app.wakeme.data.net.InviteCreatedDto
import app.wakeme.data.net.OfflineException
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
fun CirclesSection(onOpen: (String) -> Unit, vm: CirclesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadList() }
    Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        SectionTitle("Circles")
        if (s.circles.isEmpty()) Body("A circle is a private group: your flat, study group or team, with its own chat.")
        s.circles.forEach { c ->
            WMCard(Modifier.clickable { onOpen(c.id) }) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
                Body("${c.memberCount} member${if (c.memberCount == 1) "" else "s"}")
            }
        }
        QuietButton("New circle", onClick = { naming = true })
    }
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        if (d == null) {
            Body("Loading…")
            return@Column
        }
        val admin = d.circle.role == "owner" || d.circle.role == "admin"
        Text(d.circle.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Body("Being in a circle together doesn't let anyone do anything on your phone. Each person still chooses that, friend by friend.")
        PrimaryButton("Messages", onClick = { onMessages(d.circle.conversationId) })
        SecondaryButton("Invite to this circle", onClick = { vm.invite(circleId) })
        SectionTitle("Members")
        d.members.forEach { m ->
            WMCard {
                Text(m.user.displayName + if (m.user.id == s.myId) " (you)" else "", style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                StatusPill(
                    when {
                        m.role == "owner" -> "Owner"
                        m.role == "admin" -> "Admin"
                        m.user.id == s.myId -> "Member"
                        m.isFriend -> "Friend"
                        else -> "Not your friend: no permissions either way"
                    },
                    if (m.isFriend || m.user.id == s.myId) Tone.Neutral else Tone.Info,
                )
                if (admin && m.role != "owner" && m.user.id != s.myId) QuietButton("Remove ${m.user.displayName}", onClick = { vm.remove(circleId, m.user.id) })
            }
        }
        if (admin) QuietButton("Rename circle", onClick = { renaming = d.circle.name })
        DangerButton("Leave circle", onClick = { confirmLeave = true })
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
            text = { Text("Link: ${inv.url}\n\nOr they can type this code: ${inv.code}\n\nJoining the circle doesn't make anyone friends or give any permissions.") },
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
