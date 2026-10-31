package app.wakeme.ui.routines

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.FriendDto
import app.wakeme.designsystem.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class WakeThanksViewModel @Inject constructor(private val api: WakeMeApi, private val saved: SavedStateHandle) : ViewModel() {
    var friend by mutableStateOf<FriendDto?>(null); private set
    var busy by mutableStateOf(false); private set
    var sent by mutableStateOf(saved.get<Boolean>("thanksSent") == true); private set
    var message by mutableStateOf("Loading your wake buddy…"); private set
    private val clientId: String = saved.get<String>("thanksClientId") ?: ("c_" + UUID.randomUUID().toString().replace("-", "")).also { saved["thanksClientId"] = it }

    fun load(kind: String, id: String) = viewModelScope.launch {
        if (sent) { message = "Thanks sent."; return@launch }
        try {
            val me = api.me()
            val actorId = if (kind == "command") {
                val command = api.command(id)
                check(command.target.id == me.id)
                command.actor.id
            } else {
                val plan = api.plan(id)
                check(plan.owner.id == me.id)
                plan.creator.id
            }
            friend = api.friends().firstOrNull { it.user.id == actorId && it.conversationId != null }
            message = if (friend == null) "Your wake session is finished. There isn't an available buddy conversation to thank." else "Nothing is posted automatically. You can send this message or close the screen."
        } catch (_: Exception) { message = "Your wake session is finished. Connect to see your wake buddy, or close this screen." }
    }

    fun send() = viewModelScope.launch {
        val recipient = friend ?: return@launch
        if (busy || sent) return@launch
        busy = true
        try {
            api.sendMessage(recipient.conversationId ?: return@launch, "Thanks, ${recipient.user.displayName} 🦆", clientId)
            sent = true; saved["thanksSent"] = true; message = "Thanks sent."
        } catch (_: Exception) { message = "Couldn't confirm it was sent. You can retry when connected; this message uses the same receipt to avoid a duplicate." }
        finally { busy = false }
    }
}

@Composable
fun WakeThanksScreen(kind: String, id: String, onDone: () -> Unit, vm: WakeThanksViewModel = hiltViewModel()) {
    LaunchedEffect(kind, id) { vm.load(kind, id) }
    DetailScreen(onBack = onDone) {
        Text("A little thanks", style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Body(vm.message)
        vm.friend?.takeUnless { vm.sent }?.let { friend ->
            WMCard { Body("To ${friend.user.displayName}"); Body("Thanks, ${friend.user.displayName} 🦆") }
            PrimaryButton(if (vm.busy) "Sending…" else "Send thanks", enabled = !vm.busy, onClick = { vm.send() })
        }
        SecondaryButton("Done", onClick = onDone)
    }
}
