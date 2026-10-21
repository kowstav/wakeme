package app.wakeme.ui.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.local.Prefs
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.MessageDto
import app.wakeme.data.net.OfflineException
import app.wakeme.designsystem.HideFromRecents
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.DetailTopBar
import androidx.compose.foundation.layout.offset
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ConversationState(
    val loading: Boolean = true,
    val title: String = "Messages",
    val myId: String? = null,
    /** Oldest first, for display. */
    val messages: List<MessageDto> = emptyList(),
    val sending: Boolean = false,
    val offline: Boolean = false,
    val notice: String? = null,
)

/** Messages between accepted friends (or a circle). Not a grant: blocking ends the conversation. */
@HiltViewModel
class ConversationViewModel @Inject constructor(private val api: WakeMeApi, private val prefs: Prefs) : ViewModel() {
    private val _s = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _s
    private var id: String? = null
    private var poll: Job? = null

    fun open(conversationId: String) {
        if (id == conversationId) return
        id = conversationId
        viewModelScope.launch {
            _s.value = _s.value.copy(myId = prefs.currentMe()?.id)
            runCatching { api.conversations().firstOrNull { it.id == conversationId } }.getOrNull()?.let { _s.value = _s.value.copy(title = it.title) }
            refresh(markRead = true)
        }
    }

    /** Polls only while the screen is visible; push notifications cover the rest. */
    fun setVisible(visible: Boolean) {
        poll?.cancel()
        if (!visible) return
        poll = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                refresh(markRead = true)
            }
        }
    }

    private suspend fun refresh(markRead: Boolean) {
        val cid = id ?: return
        try {
            val newestFirst = api.messages(cid)
            val hadNew = newestFirst.firstOrNull()?.id != _s.value.messages.lastOrNull()?.id
            _s.value = _s.value.copy(loading = false, offline = false, messages = newestFirst.reversed())
            if (markRead && hadNew) runCatching { api.markRead(cid) }
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(loading = false, offline = true)
        } catch (e: ApiException) {
            // A blocked or removed friendship ends the conversation; say so plainly.
            _s.value = _s.value.copy(loading = false, notice = if (e.status == 404) "This conversation is no longer available." else e.message)
        }
    }

    fun send(text: String, onSent: () -> Unit) {
        val cid = id ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            _s.value = _s.value.copy(sending = true)
            try {
                api.sendMessage(cid, body)
                onSent()
                refresh(markRead = false)
            } catch (e: OfflineException) {
                _s.value = _s.value.copy(notice = "You're offline. The message wasn't sent; it's still in the box.")
            } catch (e: ApiException) {
                _s.value = _s.value.copy(notice = e.message)
            }
            _s.value = _s.value.copy(sending = false)
        }
    }

    /** Replaces my previous reaction; tapping my current one removes it. */
    fun react(m: MessageDto, emoji: String) = viewModelScope.launch {
        val mine = m.reactions.firstOrNull { it.userId == _s.value.myId }?.emoji
        try {
            if (mine == emoji) api.unreact(m.id) else api.react(m.id, emoji)
            refresh(markRead = false)
        } catch (e: Exception) {
            _s.value = _s.value.copy(notice = "Couldn't react right now.")
        }
    }

    fun report(m: MessageDto, reason: String) = viewModelScope.launch {
        try {
            api.report("message", m.id, reason, null)
            _s.value = _s.value.copy(notice = "Thanks. We'll review it. You can also block this person from their profile.")
        } catch (e: Exception) {
            _s.value = _s.value.copy(notice = "Couldn't send the report. Try again when you're online.")
        }
    }

    fun clearNotice() {
        _s.value = _s.value.copy(notice = null)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())

/** Mirrors REACTIONS in services/api/src/modules/conversations/routes.ts. */
private val reactions = listOf("👍", "❤️", "😂", "🦆", "🙌", "😴", "☀️")

private val reportReasons = listOf("harassment" to "Harassment", "spam" to "Spam", "inappropriate_content" to "Inappropriate", "other" to "Something else")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(conversationId: String, onBack: (() -> Unit)? = null, vm: ConversationViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    HideFromRecents() // private messages
    var draft by remember { mutableStateOf("") }
    var acting by remember { mutableStateOf<MessageDto?>(null) }
    var reporting by remember { mutableStateOf<MessageDto?>(null) }
    LaunchedEffect(conversationId) { vm.open(conversationId) }
    DisposableEffect(conversationId) {
        vm.setVisible(true)
        onDispose { vm.setVisible(false) }
    }

    // Bottom: the navigation bar or, when typing, the keyboard (the shell handles top and sides).
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = WakeMeTokens.spaceMd)) {
        if (onBack != null) DetailTopBar(onBack = onBack, title = s.title, modifier = Modifier.offset(x = -WakeMeTokens.spaceSm))
        else Text(s.title, style = MaterialTheme.typography.headlineSmall, color = WM.colors.textPrimary, modifier = Modifier.padding(vertical = WakeMeTokens.spaceSm))
        if (s.offline) StatusPill("You're offline. Showing messages loaded earlier.", Tone.Warning)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            items(s.messages.asReversed(), key = { it.id }) { m ->
                val mine = m.senderId != null && m.senderId == s.myId
                Bubble(m, mine, onLongPress = { if (m.state == "visible") acting = m }, modifier = Modifier.animateItem())
            }
            if (s.messages.isEmpty() && !s.loading) item { Body("No messages yet. Say hi, or send a quack from their profile.") }
        }
        // The composer is one row: the field grows to four lines, the send button stays a 48 dp
        // target beside it (a full-width yellow pill under the field was the heaviest thing on screen).
        Row(Modifier.fillMaxWidth().padding(vertical = WakeMeTokens.spaceSm), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 2000) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(WakeMeTokens.radiusLg),
            )
            val canSend = draft.isNotBlank() && !s.sending
            FilledIconButton(
                onClick = { vm.send(draft) { draft = "" } },
                enabled = canSend,
                modifier = Modifier.padding(bottom = 4.dp).size(WakeMeTokens.minTouchTarget),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = WM.colors.accent, contentColor = WM.colors.textOnAccent),
            ) {
                if (s.sending) CircularProgressIndicator(Modifier.size(WakeMeTokens.iconSizeSmall), color = WM.colors.textOnAccent, strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(WakeMeTokens.iconSizeSmall))
            }
        }
    }

    acting?.let { m ->
        val mineMsg = m.senderId != null && m.senderId == s.myId
        val myReaction = m.reactions.firstOrNull { it.userId == s.myId }?.emoji
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text("React") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                        reactions.forEach { e ->
                            ChoiceChip(e, selected = e == myReaction, onClick = { vm.react(m, e); acting = null })
                        }
                    }
                    if (myReaction != null) Body("Tap your reaction again to remove it.")
                    if (!mineMsg) TextButton(onClick = { reporting = m; acting = null }) { Text("Report message", color = WM.colors.danger) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { acting = null }) { Text("Cancel") } },
        )
    }
    reporting?.let { m ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Report this message?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                    Body("The sender isn't told who reported it.")
                    reportReasons.forEach { (code, label) ->
                        ChoiceChip(label, selected = false, onClick = { vm.report(m, code); reporting = null })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("Cancel") } },
        )
    }
    s.notice?.let { AlertDialog(onDismissRequest = vm::clearNotice, confirmButton = { TextButton(onClick = vm::clearNotice) { Text("OK") } }, text = { Text(it) }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: MessageDto, mine: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (m.state) {
        "removed" -> "Removed by moderation"
        "deleted" -> "Message deleted"
        else -> m.body.orEmpty()
    }
    val time = runCatching { timeFmt.format(Instant.parse(m.createdAt)) }.getOrDefault("")
    // Your messages are butter with a small corner toward you; theirs sit on the raised surface.
    val big = 18.dp
    val small = 6.dp
    val shape = RoundedCornerShape(topStart = big, topEnd = big, bottomStart = if (mine) big else small, bottomEnd = if (mine) small else big)
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(if (mine) WM.colors.accentContainer else WM.colors.surfaceRaised, shape)
                .border(1.dp, if (mine) WM.colors.accentContainer else WM.colors.border, shape)
                .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "React or report")
                .padding(horizontal = WakeMeTokens.spaceMd, vertical = WakeMeTokens.spaceSm)
                .semantics(mergeDescendants = true) { contentDescription = (if (mine) "You" else "Them") + ", $time: $text" },
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = if (mine) WM.colors.onAccentContainer else WM.colors.textPrimary)
            Text(
                time + if (m.reactions.isNotEmpty()) "  " + m.reactions.joinToString("") { it.emoji } else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) WM.colors.onAccentContainer else WM.colors.textSecondary,
            )
        }
    }
}
