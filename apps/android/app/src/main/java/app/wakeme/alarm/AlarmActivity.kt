package app.wakeme.alarm

import android.os.Bundle
import android.os.UserManager
import android.content.Intent
import app.wakeme.MainActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.wakeme.R
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.WakeMeJson
import app.wakeme.core.WakeSessionState
import app.wakeme.challenge.ChallengeRunner
import app.wakeme.designsystem.AlarmTimeStyle
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.Readable
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen alarm over the lock screen, designed for a sleepy person: very
 * large controls, no small print, no paywall, no feed. "Stop sound now" and
 * "I need to stop" are always available; finishing the challenge is a
 * separate outcome from silencing.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The alarm always uses the dark palette, even when the phone uses light mode.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val sessions = AlarmEntryPoints.sessions(this)
        setContent {
            var completed by rememberSaveable { mutableStateOf(false) }
            var completedKey by rememberSaveable { mutableStateOf<String?>(null) }
            var thanksRoute by rememberSaveable { mutableStateOf<String?>(null) }
            WakeMeTheme(dark = true) {
                val active by sessions.active.collectAsState()
                val a = active
                if (completed && (a == null || completedKey == a.identity)) {
                    Readable {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceLg), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
                            Text("You're up", style = MaterialTheme.typography.headlineLarge, color = WM.colors.alarmText)
                            Body("Your wake session has ended.", color = WM.colors.alarmText)
                            if (thanksRoute != null) SecondaryButton("Thank your wake buddy", onClick = {
                                startActivity(Intent(this@AlarmActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, thanksRoute))
                                finish()
                            })
                            PrimaryButton("Done", onClick = { finish() })
                        }
                    }
                } else if (a == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    key(a.planId, a.commandId, a.occurrenceKey, a.version) {
                        AlarmScreen(
                            wake = a,
                            loadChallenge = { id -> loadChallenge(id) },
                            onStop = { AlarmService.send(this, AlarmService.ACTION_STOP_SOUND) },
                            onImUp = { AlarmService.send(this, AlarmService.ACTION_IM_UP); completedKey = a.identity; completed = true; thanksRoute = makeThanksRoute(a) },
                            onComplete = { AlarmService.send(this, AlarmService.ACTION_COMPLETE); completedKey = a.identity; completed = true; thanksRoute = makeThanksRoute(a) },
                            onCheckIn = { AlarmService.send(this, AlarmService.ACTION_CHECK_IN); completedKey = a.identity; completed = true; thanksRoute = makeThanksRoute(a) },
                            onEscape = { AlarmService.send(this, AlarmService.ACTION_ESCAPE); finish() },
                        )
                    }
                }
            }
        }
    }

    private fun makeThanksRoute(wake: ActiveWake): String? {
        if (wake.helperName == null || !getSystemService(UserManager::class.java).isUserUnlocked) return null
        return wake.commandId?.let { "wake-thanks-command/$it" } ?: wake.planId?.takeUnless { app.wakeme.core.isLocalPlan(it) }?.let { "wake-thanks-plan/$it" }
    }

    /** Challenges run from the installed, signature-verified local copy. Not readable before first unlock. */
    private suspend fun loadChallenge(revisionId: String): ChallengeDefinition? {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return null
        val row = AlarmEntryPoints.db(this).revisions().get(revisionId) ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { WakeMeJson.decodeFromString(ChallengeDefinition.serializer(), row.definitionJson) }.getOrNull()
        }
    }
}

@Composable
fun AlarmScreen(
    wake: ActiveWake,
    loadChallenge: suspend (String) -> ChallengeDefinition?,
    onStop: () -> Unit,
    onImUp: () -> Unit,
    onComplete: () -> Unit,
    onEscape: () -> Unit,
    clockText: String? = null,
    onCheckIn: () -> Unit = onComplete,
) {
    var challenge by remember { mutableStateOf<ChallengeDefinition?>(null) }
    var challengeMissing by remember { mutableStateOf(false) }
    var running by rememberSaveable { mutableStateOf(false) }
    var resumes by remember { mutableStateOf(0) }
    LifecycleResumeEffect(wake.challengeRevisionId) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(wake.challengeRevisionId, resumes) {
        wake.challengeRevisionId?.let { id -> challenge = loadChallenge(id); challengeMissing = challenge == null }
    }
    val c = WM.colors
    if (running && challenge != null) {
        ChallengeRunner(
            definition = challenge!!,
            onComplete = { usedFallback -> if (usedFallback) onCheckIn() else onComplete() },
            onEscape = onEscape,
            escapeLabel = stringResource(R.string.alarm_need_to_stop),
            sessionKey = wake.identity,
            controls = {
                if (wake.ringing) PrimaryButton(stringResource(R.string.alarm_stop_sound), onClick = onStop)
            },
        )
        return
    }
    Readable(modifier = Modifier.background(c.alarmBackground)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
            ) {
                val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
                var now by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(clockText) {
                    if (clockText == null) while (true) { delay(1000); now = LocalTime.now() }
                }
                // System font with tabular figures (monospace spaced the colon out: "07 : 00").
                // Grows with font size up to 1.3× so it never overflows a phone at 2×.
                Text(clockText ?: now.format(formatter), style = AlarmTimeStyle, color = c.alarmText, maxLines = 1)
                if (wake.label.isNotBlank()) Text(wake.label, style = MaterialTheme.typography.headlineSmall, color = c.alarmText)
                wake.helperName?.let { Text(stringResource(R.string.alarm_from_friend, it), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = c.alarmText) }
                if (wake.usedFallbackSound) Body("The voice recording wasn't available. Using the built-in Gentle rise sound.", color = c.alarmText)
                when {
                    wake.playbackFailure == "audio_focus_denied" -> Body("Sound is paused because another call or audio is active. Vibration continues.", color = c.alarmText)
                    wake.ringing && wake.routeType == "bluetooth" -> Body("Playing through Bluetooth audio. Your phone may be quieter than expected.", color = c.alarmText)
                    wake.ringing && wake.routeType == "wired" -> Body("Playing through connected headphones.", color = c.alarmText)
                }
                if (wake.state == WakeSessionState.FOLLOW_UP_PENDING) Body("A follow-up alarm will ring unless you finish the challenge.", color = c.alarmText)
                when {
                    wake.challengeRevisionId == null -> PrimaryButton(stringResource(R.string.alarm_im_up), onClick = onImUp, height = WakeMeTokens.alarmControl)
                    challenge != null -> PrimaryButton(stringResource(R.string.alarm_start_challenge), onClick = { running = true }, height = WakeMeTokens.alarmControl)
                    challengeMissing -> Body("The challenge isn't available. Unlock your phone and try again. You can still stop the sound or end this wake session.", color = c.alarmText)
                }
                Body(stringResource(R.string.alarm_escape_explainer), color = c.alarmText)
            }
            Column(Modifier.padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
                if (wake.ringing) PrimaryButton(stringResource(R.string.alarm_stop_sound), onClick = onStop, height = WakeMeTokens.alarmControl)
                SecondaryButton(stringResource(R.string.alarm_need_to_stop), onClick = onEscape)
            }
        }
    }
}
