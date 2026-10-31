package app.wakeme.media

import app.wakeme.designsystem.WMIcon
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.FriendDto
import app.wakeme.designsystem.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class VoiceState(
    val own: List<VoiceSound> = emptyList(), val offers: List<OfferedSound> = emptyList(),
    val friends: List<FriendDto> = emptyList(), val recording: Boolean = false,
    val hasDraft: Boolean = false, val durationMs: Int = 0, val busy: Boolean = false,
    val playing: Boolean = false, val message: String? = null,
)

@HiltViewModel
class VoiceSoundsViewModel @Inject constructor(
    @ApplicationContext private val context: Context, private val library: SoundLibrary, private val api: WakeMeApi,
) : ViewModel() {
    var state by mutableStateOf(VoiceState()); private set
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var draft: File? = null
    private var started = 0L
    private var previewGeneration = 0

    init { refresh() }
    fun refresh() = viewModelScope.launch {
        runCatching {
            val own = library.own(); val offers = library.offers(); val friends = api.friends()
            state = state.copy(own = own, offers = offers, friends = friends)
            library.refresh()
        }.onFailure { state = state.copy(message = "Couldn't refresh sounds. Built-in sounds still work.") }
    }

    fun startRecording() {
        if (state.busy || state.recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = state.copy(message = "Microphone access isn't allowed. Built-in sounds still work."); return
        }
        discard()
        try {
            val file = File.createTempFile("voice-draft-", ".m4a", context.cacheDir)
            draft = file
            val native = MediaRecorder(context)
            recorder = native
            native.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(48_000); setAudioSamplingRate(44_100)
                // Leave room for the AAC encoder's final frames within the server's 30 s limit.
                setMaxDuration(MAX_SOUND_MS - 500); setMaxFileSize(MAX_SOUND_BYTES.toLong())
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording() }
                setOnErrorListener { _, _, _ -> discard(); state = state.copy(message = "Recording stopped. Please try again.") }
                prepare(); start()
            }
            started = SystemClock.elapsedRealtime()
            state = state.copy(recording = true, message = null)
        } catch (_: Exception) {
            discard(); state = state.copy(message = "Couldn't record. Check microphone access, then try again.")
        }
    }

    fun stopRecording() {
        val active = recorder ?: return
        recorder = null
        val ms = (SystemClock.elapsedRealtime() - started).toInt().coerceIn(1, MAX_SOUND_MS)
        val valid = runCatching { active.stop(); (draft?.length() ?: 0) in 1..MAX_SOUND_BYTES.toLong() }.getOrDefault(false)
        active.release()
        if (!valid) { draft?.delete(); draft = null }
        state = state.copy(recording = false, hasDraft = valid, durationMs = if (valid) ms else 0,
            message = if (valid) null else "That recording was too short. Try again when you're ready.")
    }

    fun stopForBackground() { stopRecording(); stopPreview() }
    fun stopPreview() { previewGeneration++; player?.let { runCatching { it.stop() }; it.release() }; player = null; state = state.copy(playing = false) }
    private suspend fun play(file: File) {
        stopPreview()
        val next = MediaPlayer()
        player = next
        try {
            withContext(Dispatchers.IO) {
                next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                next.setDataSource(file.absolutePath); next.prepare()
            }
            if (player !== next) return
            next.setOnCompletionListener { stopPreview() }; next.setOnErrorListener { _, _, _ -> stopPreview(); true }
            next.start(); state = state.copy(playing = true)
        } catch (_: Exception) { stopPreview(); state = state.copy(message = "Couldn't preview this recording.") }
    }
    fun previewDraft() = viewModelScope.launch { draft?.takeIf { state.hasDraft }?.let { play(it) } }
    fun preview(sound: VoiceSound) = task {
        stopPreview()
        val generation = previewGeneration
        val file = library.preview(sound.id)
        if (generation == previewGeneration) play(file)
    }

    fun upload(rightsAttested: Boolean) = task {
        stopPreview()
        val recording = draft ?: return@task
        library.upload(recording, state.durationMs, rightsAttested)
        discard()
        state = state.copy(message = "Uploaded for checking. Refresh to see when it's ready. Nothing was sent to a friend.")
        refresh()
    }
    fun decide(sound: VoiceSound, approve: Boolean) = task { library.decide(sound.id, approve); refresh() }
    fun offer(sound: VoiceSound, friend: FriendDto) = task {
        library.offer(sound.id, friend.user.id)
        state = state.copy(message = "Offered to ${friend.user.displayName}. They decide whether to approve it.")
    }
    fun delete(sound: VoiceSound) = task { library.delete(sound.id); refresh() }
    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        if (state.busy) return@launch
        state = state.copy(busy = true)
        try { block() } catch (_: Exception) { state = state.copy(message = "Couldn't finish that action. Your alarms still use an available sound. Try again when connected.") }
        finally { state = state.copy(busy = false) }
    }
    fun discard() {
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
        stopPreview(); draft?.delete(); draft = null
        state = state.copy(recording = false, hasDraft = false, durationMs = 0)
    }
    override fun onCleared() { discard() }
}

private fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun VoiceSoundsScreen(onBack: (() -> Unit)? = null, vm: VoiceSoundsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val s = vm.state
    var rights by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var rationale by remember { mutableStateOf(false) }
    var offering by remember { mutableStateOf<VoiceSound?>(null) }
    var deleting by remember { mutableStateOf<VoiceSound?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) vm.startRecording()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stopForBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.stopForBackground() }
    }
    LaunchedEffect(s.hasDraft) { rights = false }
    DetailScreen(onBack = onBack) {
        Text("Voice sounds", style = MaterialTheme.typography.headlineMedium, color = WM.colors.textPrimary)
        Body("Up to 30 seconds.")
        if (s.recording) {
            StatusPill("Microphone is recording · stops at 30 seconds", Tone.Warning)
            PrimaryButton("Stop recording", onClick = vm::stopRecording, icon = WMIcon.Stop)
        } else {
            SecondaryButton("Record a voice sound", enabled = !s.busy, icon = WMIcon.Mic, onClick = {
                val activity = context.activity()
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startRecording()
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> rationale = true
                    denied -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            })
        }
        if (denied) Body("Microphone access is off.")
        if (s.hasDraft) {
            WMCard {
                Body("Your recording · ${(s.durationMs + 999) / 1000} seconds · only on this screen until you upload")
                SecondaryButton("Preview recording", onClick = { vm.previewDraft() }, enabled = !s.busy, icon = WMIcon.Speaker)
                SettingRow("I recorded this or have the right to use it", "Only upload voices and sounds you have permission to share.", rights) { rights = it }
                PrimaryButton(if (s.busy) "Uploading…" else "Upload for checking", enabled = rights && !s.busy, onClick = { vm.upload(rights) })
                QuietButton("Discard recording", enabled = !s.busy, onClick = vm::discard)
            }
        }
        if (s.playing) SecondaryButton("Stop preview", onClick = vm::stopPreview, icon = WMIcon.Stop)
        s.message?.let { Body(it) }
        SecondaryButton("Refresh sounds", enabled = !s.busy && !s.recording, onClick = { vm.refresh() }, icon = WMIcon.Repeat)
        SectionTitle("Your uploads")
        if (s.own.isEmpty()) Body("No recordings uploaded yet.")
        for (sound in s.own) WMCard {
            Body("Voice recording ${sound.id.take(6)} · ${when (sound.state) { "clean" -> "Ready"; "rejected" -> "Couldn't be approved"; else -> "Checking · not usable yet" }}")
            if (sound.state == "clean") {
                SecondaryButton("Preview sound", enabled = !s.busy, onClick = { vm.preview(sound) }, icon = WMIcon.Speaker)
                SecondaryButton("Offer to a friend", enabled = !s.busy, onClick = { offering = sound }, icon = WMIcon.Send)
            }
            QuietButton("Delete sound", enabled = !s.busy, onClick = { deleting = sound })
        }
        SectionTitle("From friends")
        if (s.offers.isEmpty()) Body("No sound offers.")
        for (offered in s.offers) WMCard {
            Body("From ${offered.offeredBy.displayName} · ${if (offered.state == "approved") "Approved for your library" else "Waiting for your approval"}")
            SecondaryButton("Preview offered sound", enabled = !s.busy, onClick = { vm.preview(offered.media) }, icon = WMIcon.Speaker)
            if (offered.state != "approved") PrimaryButton("Approve sound", enabled = !s.busy, onClick = { vm.decide(offered.media, true) }, icon = WMIcon.Check)
            QuietButton(if (offered.state == "approved") "Remove approval" else "Decline sound", enabled = !s.busy, onClick = { vm.decide(offered.media, false) })
        }
        Gap()
    }
    if (rationale) AlertDialog(onDismissRequest = { rationale = false }, title = { Text("Allow microphone access?") }, text = { Text("Wake Me records only when you choose Record, for up to 30 seconds. Leaving this screen stops recording. Nothing uploads until you choose Upload.") }, confirmButton = { TextButton(onClick = { rationale = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } })
    offering?.let { sound -> AlertDialog(onDismissRequest = { offering = null }, title = { Text("Offer this sound") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (friend in s.friends) TextButton(onClick = { offering = null; vm.offer(sound, friend) }) { Text(friend.user.displayName) }
            if (s.friends.isEmpty()) Body("Invite a friend first.")
        }
    }, confirmButton = { TextButton(onClick = { offering = null }) { Text("Cancel") } }) }
    deleting?.let { sound -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this sound?") }, text = { Text("It will be removed from your library and friends' libraries. Alarms use a built-in fallback when a recording isn't available.") }, confirmButton = { TextButton(onClick = { deleting = null; vm.delete(sound) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
