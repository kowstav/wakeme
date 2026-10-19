package app.wakeme.alarm

import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
        val sessions = AlarmEntryPoints.sessions(this)
        setContent {
            WakeMeTheme(dark = true) {
                val active by sessions.active.collectAsState()
                val a = active
                if (a == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    AlarmScreen(
                        wake = a,
                        loadChallenge = { id -> loadChallenge(id) },
                        onStop = { AlarmService.send(this, AlarmService.ACTION_STOP_SOUND) },
                        onImUp = { AlarmService.send(this, AlarmService.ACTION_IM_UP); finish() },
                        onComplete = { AlarmService.send(this, AlarmService.ACTION_COMPLETE); finish() },
                        onEscape = { AlarmService.send(this, AlarmService.ACTION_ESCAPE); finish() },
                    )
                }
            }
        }
    }

    /** Challenges run from the installed, signature-verified local copy. Not readable before first unlock. */
    private suspend fun loadChallenge(revisionId: String): ChallengeDefinition? {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return null
        val row = AlarmEntryPoints.db(this).revisions().get(revisionId) ?: return null
        return runCatching { WakeMeJson.decodeFromString(ChallengeDefinition.serializer(), row.definitionJson) }.getOrNull()
    }
}

@Composable
private fun AlarmScreen(
    wake: ActiveWake,
    loadChallenge: suspend (String) -> ChallengeDefinition?,
    onStop: () -> Unit,
    onImUp: () -> Unit,
    onComplete: () -> Unit,
    onEscape: () -> Unit,
) {
    var challenge by remember { mutableStateOf<ChallengeDefinition?>(null) }
    var challengeMissing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(wake.challengeRevisionId) {
        wake.challengeRevisionId?.let { id -> challenge = loadChallenge(id); challengeMissing = challenge == null }
    }
    val c = WM.colors
    Column(
        Modifier.fillMaxSize().background(c.alarmBackground).safeDrawingPadding().padding(WakeMeTokens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
    ) {
        if (running && challenge != null) {
            ChallengeRunner(
                definition = challenge!!,
                onComplete = { onComplete() },
                onEscape = onEscape,
                escapeLabel = stringResource(R.string.alarm_need_to_stop),
            )
            return@Column
        }
        Text(
            LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            fontSize = 76.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = c.alarmText,
        )
        if (wake.label.isNotBlank()) Text(wake.label, style = MaterialTheme.typography.headlineSmall, color = c.alarmText)
        wake.helperName?.let { Text(stringResource(R.string.alarm_from_friend, it), style = MaterialTheme.typography.titleLarge, color = c.alarmText) }
        when {
            wake.playbackFailure == "audio_focus_denied" -> Body("Sound is paused because another call or audio is active. Vibration continues.", color = c.alarmText)
            wake.ringing && wake.routeType == "bluetooth" -> Body("Playing through Bluetooth audio. Your phone may be quieter than expected.", color = c.alarmText)
            wake.ringing && wake.routeType == "wired" -> Body("Playing through connected headphones.", color = c.alarmText)
        }
        if (wake.state == WakeSessionState.FOLLOW_UP_PENDING) Body("A follow-up alarm will ring unless you finish the challenge.", color = c.alarmText)

        if (wake.ringing) PrimaryButton(stringResource(R.string.alarm_stop_sound), onClick = onStop, height = WakeMeTokens.alarmControl)
        when {
            wake.challengeRevisionId == null -> PrimaryButton(stringResource(R.string.alarm_im_up), onClick = onImUp, height = WakeMeTokens.alarmControl)
            challenge != null -> PrimaryButton(stringResource(R.string.alarm_start_challenge), onClick = { running = true }, height = WakeMeTokens.alarmControl)
            challengeMissing -> Body("Unlock your phone to start the challenge. You can still stop the sound.", color = c.alarmText)
        }
        SecondaryButton(stringResource(R.string.alarm_need_to_stop), onClick = onEscape)
        Body(stringResource(R.string.alarm_escape_explainer), color = c.alarmText)
    }
}
