package app.wakeme.ui.readiness

import app.wakeme.designsystem.WMIcon
import android.content.Context
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import app.wakeme.R
import app.wakeme.alarm.AlarmAudio
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DetailScreen
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.readiness.Readiness
import app.wakeme.readiness.ReadinessItem
import app.wakeme.readiness.ReadinessState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

fun readinessCopy(item: ReadinessItem): Int = when (item) {
    ReadinessItem.NOTIFICATIONS -> R.string.ready_notifications
    ReadinessItem.EXACT_ALARMS -> R.string.ready_exact_alarm
    ReadinessItem.FULL_SCREEN -> R.string.ready_full_screen
    ReadinessItem.BATTERY -> R.string.ready_battery
    ReadinessItem.ALARM_VOLUME -> R.string.ready_volume
}

/** What a sound test showed, in literal words. Only a real test shows what is audible. */
fun routeText(route: String): String = when (route) {
    "speaker" -> "It played through the phone's speaker."
    "bluetooth" -> "It played through a Bluetooth device, not the phone's speaker. Alarms may do the same while it's connected."
    "wired" -> "It played through wired headphones."
    "none" -> "It didn't play."
    else -> "It played, but the output couldn't be identified."
}

data class ReadyState(
    val readiness: ReadinessState = ReadinessState(emptyList()),
    val testing: Boolean = false,
    val testResult: String? = null,
    /** True once a test actually started sound on this phone. */
    val testPlayed: Boolean = false,
)

@HiltViewModel
class ReadyViewModel @Inject constructor(@ApplicationContext private val context: Context, private val readiness: Readiness) : ViewModel() {
    private val _s = MutableStateFlow(ReadyState())
    val state: StateFlow<ReadyState> = _s

    /** Re-read on every resume: the user may have just changed a permission in system settings. */
    fun refresh() {
        _s.value = _s.value.copy(readiness = readiness.state())
        viewModelScope.launch { readiness.reportCapabilities() }
    }

    fun settingsIntent(item: ReadinessItem) = readiness.settingsIntent(item)

    /** A real test with a bundled sound on the alarm stream, reporting the route actually used. */
    fun testSound(soundId: String = "bundled:rise.gentle") = viewModelScope.launch {
        if (_s.value.testing) return@launch
        _s.value = _s.value.copy(testing = true, testResult = null)
        val audio = AlarmAudio(context)
        val report = audio.start(soundId)
        var played = false
        val result = when {
            report.failure == "audio_focus_denied" -> "Another app (for example a call) is using audio, so the test didn't play. A real alarm would vibrate and show on screen."
            !report.started -> "The sound didn't start. Check the alarm volume and try again."
            else -> {
                played = true
                delay(4_000)
                routeText(audio.currentRoute()) + if (readiness.state().missing.contains(ReadinessItem.ALARM_VOLUME)) " Your alarm volume is at zero." else ""
            }
        }
        audio.stop()
        _s.value = _s.value.copy(testing = false, testResult = result, testPlayed = _s.value.testPlayed || played)
    }
}

/** The readiness checklist and sound test, embeddable in other screens. */
@Composable
fun ReadinessSection(vm: ReadyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        SectionTitle(stringResource(R.string.ready_title))
        when {
            s.readiness.missing.isEmpty() -> StatusPill(stringResource(R.string.ready_all_good), Tone.Success)
            !s.readiness.readyForWake -> StatusPill("Wake-ups can't ring reliably on this phone yet", Tone.Warning)
            else -> StatusPill("Ready for wake-ups · ${s.readiness.missing.size} optional step${if (s.readiness.missing.size == 1) "" else "s"}", Tone.Info)
        }
        s.readiness.missing.forEach { r ->
            WMCard {
                Body(stringResource(readinessCopy(r)), color = WM.colors.textPrimary)
                SecondaryButton("Open settings", onClick = { runCatching { context.startActivity(vm.settingsIntent(r)) } }, icon = WMIcon.ArrowUpRight)
            }
        }
        SecondaryButton(if (s.testing) "Playing…" else stringResource(R.string.ready_test_sound), onClick = { vm.testSound() }, enabled = !s.testing)
        Body(stringResource(R.string.ready_test_note) + " Try it with your headphones connected too.")
        s.testResult?.let { Body(it, color = WM.colors.textPrimary) }
    }
}

/**
 * Onboarding steps 7–9 (doc 03 §5) for someone setting up their own wake-up,
 * and the "Make this phone ready" destination. Alarm access is asked for here,
 * at wake setup, never at sign-up. Ends with Ready or the exact next step.
 */
@Composable
fun ReadyScreen(onContinue: () -> Unit, continueLabel: String = "Set my first wake-up", onSkip: (() -> Unit)? = null, onBack: (() -> Unit)? = null, vm: ReadyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    // Opened from Routines it is a pushed screen with a back bar; during setup it has none.
    DetailScreen(onBack = onBack) {
        Text("Let's make sure your alarm rings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        Body("Android needs your OK for alarms to ring on time and show over the lock screen. Then play a test so you know how it sounds here.")
        ReadinessSection(vm)
        val nextStep = s.readiness.missing.firstOrNull { it == ReadinessItem.EXACT_ALARMS || it == ReadinessItem.NOTIFICATIONS }
        when {
            nextStep != null -> Body("Next step: ${stringResource(readinessCopy(nextStep))}", color = WM.colors.textPrimary)
            !s.testPlayed -> Body("Next step: play the test sound.", color = WM.colors.textPrimary)
            else -> StatusPill("Ready on this phone", Tone.Success)
        }
        PrimaryButton(continueLabel, onClick = onContinue, enabled = s.readiness.readyForWake)
        if (!s.readiness.readyForWake) Body("You can still add friends and send quacks while you sort this out.")
        onSkip?.let { QuietButton("Do this later", onClick = it) }
    }
}
