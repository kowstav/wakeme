package app.wakeme.focus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.wakeme.challenge.ChallengeRunner
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.ChallengeStep
import app.wakeme.core.FOCUS_BREAK_MINUTES
import app.wakeme.core.FOCUS_MAX_BREAKS
import app.wakeme.core.Fallback
import app.wakeme.core.FocusState
import app.wakeme.core.focusState
import app.wakeme.core.WakeMeJson
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/**
 * The focus shield (doc 03 §4E, ADR 0014): shown over an app the person chose to keep closed.
 * It explains the goal and offers three ways on: back to what they were doing, a short break
 * earned with a challenge (or the accessible check-in instead), and "End focus", which always works.
 */
@AndroidEntryPoint
class FocusShieldActivity : ComponentActivity() {
    @Inject lateinit var store: FocusStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store.reload()
        setContent { WakeMeTheme { Shield(store, onLeave = ::goHome, onDone = ::finish) } }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_APP = "app"
    }
}

@Composable
private fun Shield(store: FocusStore, onLeave: () -> Unit, onDone: () -> Unit) {
    val session by store.session.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var earning by rememberSaveable(session?.id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val s = session
    val state = s?.let { focusState(it, now) }
    // Ended, finished or on a break meanwhile: nothing to keep closed.
    LaunchedEffect(state) { if (state !is FocusState.Active) onDone() }
    if (s == null || state !is FocusState.Active) return

    if (earning) {
        // Save only this small definition, so rotation doesn't replace the sums mid-answer.
        val raw = rememberSaveable(s.id, s.breaksTaken) {
            WakeMeJson.encodeToString(ChallengeDefinition.serializer(), breakChallenge())
        }
        val definition = remember(raw) { WakeMeJson.decodeFromString(ChallengeDefinition.serializer(), raw) }
        ChallengeRunner(
            definition = definition,
            onComplete = { if (store.takeBreak()) onDone() else earning = false },
            onEscape = { earning = false },
            escapeLabel = "Back",
            sessionKey = "${s.id}:${s.breaksTaken}",
            controls = { DangerButton("End focus", onClick = { store.end(); onDone() }) },
        )
        return
    }
    val minutesLeft = (state.remainingMs + 59_999) / 60_000
    val breaksLeft = FOCUS_MAX_BREAKS - s.breaksTaken
    Column(
        Modifier.fillMaxSize().background(WM.colors.surface).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
    ) {
        Text(if (s.friendName != null) "Focusing with ${s.friendName}" else "You're focusing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Body("$minutesLeft min left.", color = WM.colors.textPrimary)
        PrimaryButton("Back to what I was doing", onClick = onLeave)
        if (breaksLeft > 0) {
            SecondaryButton("Take a $FOCUS_BREAK_MINUTES-minute break", onClick = { earning = true })
            Body("$breaksLeft break${if (breaksLeft == 1) "" else "s"} left.")
        } else {
            Body("No breaks left this session.")
        }
        DangerButton("End focus", onClick = { store.end(); onDone() })
    }
}

/** Two small sums, checked on the phone; the runner always offers the accessible check-in instead. */
private fun breakChallenge(r: Random = Random.Default): ChallengeDefinition {
    fun sum(id: String): ChallengeStep.MathStep {
        val a = r.nextLong(2, 20)
        val b = r.nextLong(2, 20)
        return ChallengeStep.MathStep(id, listOf(a, b), "+", a + b)
    }
    return ChallengeDefinition(1, "Earn a short break", "sequence", "easy", 60, listOf(sum("b1"), sum("b2")), "local_deterministic", Fallback("accessible_checkin"))
}
