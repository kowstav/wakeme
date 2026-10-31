package app.wakeme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import app.wakeme.alarm.ActiveWake
import app.wakeme.alarm.AlarmScreen
import app.wakeme.challenge.ChallengeRunner
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.ChallengeStep
import app.wakeme.core.Fallback
import app.wakeme.core.WakeSessionState
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.WakeMeTheme
import com.android.tools.screenshot.PreviewTest

/** Doc 06: the alarm, quiz and chess flow across window sizes, with the same escape controls. */
@PreviewTest
@FormFactorPreviews
@Preview(name = "Phone large text", device = Devices.PHONE, fontScale = 2f)
@Preview(name = "Small landscape", widthDp = 640, heightDp = 360)
@Composable
fun WakeAlarm() = WakeMeTheme(dark = true) {
    AlarmScreen(
        ActiveWake(1, "p", null, "2026-10-20T07:00:00Z", 1,
            "Time for the morning study group", "Maya", null,
            WakeSessionState.RINGING, false, true, startedAt = 1L),
        loadChallenge = { null }, onStop = {}, onImUp = {}, onComplete = {}, onEscape = {}, clockText = "07:00",
    )
}

private fun definition(step: ChallengeStep) = ChallengeDefinition(
    1, "Morning check-in", "sequence", "easy", 120, listOf(step), "local_deterministic", Fallback(),
)

@PreviewTest
@FormFactorPreviews
@Preview(name = "Phone large text", device = Devices.PHONE, fontScale = 2f)
@Preview(name = "Small landscape", widthDp = 640, heightDp = 360)
@Composable
fun WakeQuiz() = WakeMeTheme(dark = true) {
    ChallengeRunner(
        definition(ChallengeStep.MultipleChoice("q", "Which cafe do we call headquarters?", listOf("Bean House", "The long walk home", "The place by the library"), 0)),
        onComplete = {}, onEscape = {}, escapeLabel = "I need to stop",
        controls = { SecondaryButton("Stop sound now", onClick = {}) },
    )
}

@PreviewTest
@FormFactorPreviews
@Preview(name = "Phone dark", device = Devices.PHONE, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WakeChess() = WakeMeTheme {
    ChallengeRunner(
        definition(ChallengeStep.Chess("c", "White to move: find checkmate in one.", "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1", listOf("e1e8"))),
        onComplete = {}, onEscape = {}, escapeLabel = "End wake session",
    )
}
