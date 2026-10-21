package app.wakeme.challenge

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.lerp
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WMMotion
import app.wakeme.designsystem.rememberHaptics
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.ChallengeStep
import app.wakeme.core.StepResponse
import app.wakeme.core.verifyStep
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.ReadableWidth
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WakeMeTokens
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay

/**
 * Renders a declarative challenge with native components and verifies every
 * answer on the device (no network). The escape is always on screen and is
 * never hidden behind the challenge; the accessible check-in is offered for
 * every step. Mistakes are met with a calm retry, never a penalty.
 */
@Composable
fun ChallengeRunner(
    definition: ChallengeDefinition,
    onComplete: (usedFallback: Boolean) -> Unit,
    onEscape: () -> Unit,
    escapeLabel: String,
    modifier: Modifier = Modifier,
    sessionKey: String = "practice",
    controls: @Composable ColumnScope.() -> Unit = {},
) {
    // A follow-up for this occurrence keeps progress; another occurrence/revision starts afresh.
    key(sessionKey, definition) {
        ChallengeContent(definition, onComplete, onEscape, escapeLabel, modifier, controls)
    }
}

@Composable
private fun ChallengeContent(
    definition: ChallengeDefinition,
    onComplete: (Boolean) -> Unit,
    onEscape: () -> Unit,
    escapeLabel: String,
    modifier: Modifier,
    controls: @Composable ColumnScope.() -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var fallback by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val startedAt = rememberSaveable { SystemClock.elapsedRealtime() }
    var timedOut by rememberSaveable { mutableStateOf(false) }
    val currentOnComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(startedAt, definition.maxDurationSeconds) {
        val duration = definition.maxDurationSeconds.coerceIn(1, 3600) * 1000L
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        delay((duration - elapsed).coerceAtLeast(0))
        timedOut = true
        fallback = true // Expiry offers a self-check, never reports an unsolved challenge as solved.
    }
    val step = definition.steps.getOrNull(index)
    val finish: (Boolean) -> Unit = { usedFallback ->
        if (!finished) {
            finished = true
            currentOnComplete(usedFallback)
        }
    }
    val advance = {
        feedback = null
        if (index + 1 >= definition.steps.size) finish(false) else index += 1
    }
    val haptics = rememberHaptics()
    val check = { at: Int, r: StepResponse ->
        // Ignore a late scanner/timer callback, a second tap from the step just completed, or a tap
        // on the previous step while it fades out.
        val current = definition.steps.getOrNull(at)
        if (!finished && !fallback && index == at && current != null) {
            if (verifyStep(current, r)) {
                haptics.selection()
                advance()
            } else {
                haptics.wrongAnswer()
                feedback = "Not quite. Take your time and try again."
            }
        }
    }

    Box(modifier.fillMaxSize().background(WM.colors.surface).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ReadableWidth).fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
            ) {
                Text(definition.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
                if (timedOut) Body("Time's up. Use the simple check-in or end this session.")
                else Body("Step ${index + 1} of ${definition.steps.size}")
                StepProgress(done = if (finished) definition.steps.size else index, total = definition.steps.size)
                if (fallback || step == null) {
                    AccessibleCheckIn(definition.fallback.prompt) { finish(true) }
                } else {
                    // Each step arrives from the reading direction and fades in; the previous one
                    // fades out. Input works at once (no delay), and a stale tap is ignored above.
                    AnimatedContent(
                        targetState = index,
                        transitionSpec = {
                            (slideInHorizontally(WMMotion.enter()) { it / 12 } + fadeIn(WMMotion.enter())) togetherWith fadeOut(WMMotion.exit())
                        },
                        label = "step",
                    ) { at ->
                        Column(verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
                            when (val s = definition.steps.getOrNull(at)) {
                                is ChallengeStep.MultipleChoice -> MultipleChoice(s) { check(at, StepResponse.Index(it)) }
                                is ChallengeStep.ShortAnswer -> TextAnswer(s.prompt, KeyboardType.Text) { check(at, StepResponse.Text(it)) }
                                is ChallengeStep.MathStep -> TextAnswer("${s.operands.joinToString(" ${symbol(s.operator)} ")} = ?", KeyboardType.Number) { check(at, StepResponse.Text(it)) }
                                is ChallengeStep.MemoryOrder -> MemoryOrder(s) { check(at, StepResponse.Order(it)) }
                                is ChallengeStep.Chess -> ChessStep(s) { check(at, StepResponse.Move(it)) }
                                is ChallengeStep.Timer -> TimerStep(s) { check(at, StepResponse.Elapsed(it)) }
                                is ChallengeStep.QrCode -> QrStep(s) { check(at, StepResponse.Text(it)) }
                                is ChallengeStep.SelfAttested -> Checklist(s) { check(at, StepResponse.Checked(it)) }
                                null -> Unit
                            }
                        }
                    }
                    // Calm, not punishing: no shake, no red. Announced politely to TalkBack.
                    AnimatedVisibility(visible = feedback != null, enter = fadeIn(WMMotion.standard()), exit = fadeOut(WMMotion.quick())) {
                        StatusPill(feedback ?: "", Tone.Info, announce = true)
                    }
                }
            }
            // Kept outside the scrolling puzzle. Bars and the IME are consumed above, so these
            // controls remain reachable on small windows and with a long quiz or chess board.
            Column(Modifier.padding(horizontal = WakeMeTokens.spaceMd, vertical = WakeMeTokens.spaceSm)) {
                controls()
                if (!fallback && step != null) QuietButton("Use the simple check-in instead", onClick = { fallback = true })
                SecondaryButton(escapeLabel, onClick = onEscape)
            }
        }
    }
}

private fun symbol(op: String) = when (op) {
    "*" -> "×"
    "/" -> "÷"
    "-" -> "−"
    else -> op
}

@Composable
private fun MultipleChoice(step: ChallengeStep.MultipleChoice, onPick: (Int) -> Unit) {
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    step.options.forEachIndexed { i, option -> SecondaryButton(option, onClick = { onPick(i) }) }
}

@Composable
private fun TextAnswer(prompt: String, keyboard: KeyboardType, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Text(prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    OutlinedTextField(
        value = text,
        label = { Text("Your answer") },
        onValueChange = { text = it.take(80) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = MaterialTheme.typography.headlineSmall,
    )
    PrimaryButton("Check", onClick = { onSubmit(text) }, enabled = text.isNotBlank())
}

@Composable
private fun MemoryOrder(step: ChallengeStep.MemoryOrder, onSubmit: (List<String>) -> Unit) {
    var showing by rememberSaveable { mutableStateOf(true) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val shuffled = rememberSaveable(step.id) { step.items.shuffled() }
    LaunchedEffect(showing) {
        if (!showing) return@LaunchedEffect
        delay(step.displaySeconds * 1000L)
        showing = false
    }
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    if (showing) {
        Text(step.items.joinToString("  →  "), style = MaterialTheme.typography.headlineSmall, color = WM.colors.textPrimary)
        QuietButton("I've got it", onClick = { showing = false })
    } else {
        Body(if (picked.isEmpty()) "Tap them in the same order." else picked.joinToString(" → "))
        shuffled.filter { it !in picked }.forEach { item ->
            SecondaryButton(item, onClick = {
                picked += item
                if (picked.size == step.items.size) {
                    onSubmit(picked.toList())
                    picked = emptyList()
                }
            })
        }
        if (picked.isNotEmpty()) QuietButton("Start over", onClick = { picked = emptyList() })
        QuietButton("Show the list again", onClick = { picked = emptyList(); showing = true })
    }
}

/** 8×8 board from FEN; tap a piece then a square. A typed move is the accessible alternative. */
@Composable
private fun ChessStep(step: ChallengeStep.Chess, onMove: (String) -> Unit) {
    val board = remember(step.fen) { parseFenBoard(step.fen) }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    val whiteToMove = step.fen.split(" ").getOrNull(1) != "b"
    // Dark squares are a warm grey mixed from the outline colour, so the board reads as a board
    // in both themes (surfaceSunken on surfaceRaised was 1.06:1).
    val darkSquare = lerp(WM.colors.surfaceRaised, WM.colors.outline, 0.32f)
    Column(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, WM.colors.border)) {
        val ranks = if (whiteToMove) (8 downTo 1) else (1..8)
        val files = if (whiteToMove) ('a'..'h').toList() else ('a'..'h').reversed()
        for (rank in ranks) {
            Row(Modifier.weight(1f)) {
                for (file in files) {
                    val sq = "$file$rank"
                    val piece = board[sq]
                    val dark = (file - 'a' + rank) % 2 == 1 // a1 is a dark square
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(if (from == sq) WM.colors.accent else if (dark) darkSquare else WM.colors.surfaceRaised)
                            .clickable {
                                val f = from
                                if (f == null) {
                                    if (piece != null) from = sq
                                } else {
                                    val promote = board[f]?.lowercaseChar() == 'p' && (rank == 8 || rank == 1)
                                    onMove(f + sq + if (promote) "q" else "")
                                    from = null
                                }
                            }
                            .semantics { contentDescription = "$sq ${piece?.let { pieceName(it) } ?: "empty"}" },
                        contentAlignment = Alignment.Center,
                    ) { Text(piece?.let { glyph(it) } ?: "", fontSize = 26.sp, color = WM.colors.textPrimary, textAlign = TextAlign.Center) }
                }
            }
        }
    }
    Body("Or type the move, like a1a8 (from square, to square).")
    OutlinedTextField(value = typed, label = { Text("Move, from square to square") }, onValueChange = { typed = it.take(5) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    PrimaryButton("Play move", onClick = { onMove(typed) }, enabled = typed.length >= 4)
}

private fun parseFenBoard(fen: String): Map<String, Char> {
    val out = HashMap<String, Char>()
    fen.split(" ").first().split("/").forEachIndexed { r, row ->
        var file = 0
        for (ch in row) {
            if (ch.isDigit()) file += ch.digitToInt() else {
                out["${'a' + file}${8 - r}"] = ch
                file++
            }
        }
    }
    return out
}

private fun glyph(p: Char) = mapOf('K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙", 'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟")[p] ?: ""
private fun pieceName(p: Char) = (if (p.isUpperCase()) "white " else "black ") + mapOf('k' to "king", 'q' to "queen", 'r' to "rook", 'b' to "bishop", 'n' to "knight", 'p' to "pawn")[p.lowercaseChar()]

/** Measured with the monotonic clock so changing the wall clock can't skip it. */
@Composable
private fun TimerStep(step: ChallengeStep.Timer, onDone: (Double) -> Unit) {
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val currentOnDone by rememberUpdatedState(onDone)
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    if (startedAt == 0L) {
        PrimaryButton("Start ${step.seconds / 60} min ${step.seconds % 60} s", onClick = { startedAt = SystemClock.elapsedRealtime() })
    } else {
        LaunchedEffect(startedAt) {
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                if (elapsed >= step.seconds) {
                    currentOnDone(elapsed.toDouble())
                    break
                }
                delay(500)
            }
        }
        val left = (step.seconds - elapsed).coerceAtLeast(0)
        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium, color = WM.colors.textPrimary)
        Body("This is a timer and a self-check. It can't tell what you did.")
    }
}

@Composable
private fun QrStep(step: ChallengeStep.QrCode, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    PrimaryButton("Scan the code", onClick = {
        // Google code scanner: no camera permission for Wake Me; the scan UI is provided by Play services.
        val scanner = GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        scanner.startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(onCode) }
            .addOnFailureListener { error = "Scanning isn't available. Type the code printed under it instead." }
    })
    error?.let { Body(it) }
    Body("Or type the letters printed under the code.")
    OutlinedTextField(value = typed, label = { Text("Code printed under the QR code") }, onValueChange = { typed = it.take(16) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    SecondaryButton("Check code", onClick = { onCode(typed) }, enabled = typed.length >= 6)
}

@Composable
private fun Checklist(step: ChallengeStep.SelfAttested, onSubmit: (List<Boolean>) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(List(step.items.size) { false }) }
    Text(step.prompt, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    step.items.forEachIndexed { i, item ->
        Row(Modifier.fillMaxWidth().clickable { checked = checked.mapIndexed { j, value -> if (i == j) !value else value } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked[i], onCheckedChange = { value -> checked = checked.mapIndexed { j, old -> if (i == j) value else old } })
            Text(item, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = WM.colors.textPrimary)
        }
    }
    step.alternative?.let { Body(it) }
    PrimaryButton("Done", onClick = { onSubmit(checked.toList()) }, enabled = checked.all { it })
}

/** Low-demand alternative for any step: two taps a few seconds apart. Counts as a self-check. */
@Composable
private fun AccessibleCheckIn(prompt: String?, onDone: () -> Unit) {
    var first by rememberSaveable { mutableLongStateOf(0L) }
    var ready by remember { mutableStateOf(false) }
    Text(prompt ?: "Tap \"I'm awake\", then tap it again when it lights up.", style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary)
    if (first == 0L) {
        PrimaryButton("I'm awake", onClick = { first = SystemClock.elapsedRealtime() }, height = WakeMeTokens.alarmControl)
    } else {
        LaunchedEffect(first) {
            delay((5000 - (SystemClock.elapsedRealtime() - first).coerceAtLeast(0)).coerceAtLeast(0))
            ready = true
        }
        PrimaryButton(if (ready) "I'm awake (again)" else "Just a moment…", onClick = onDone, enabled = ready, height = WakeMeTokens.alarmControl)
    }
}

/** One segment per step; finished steps fill with success. Visual only: "Step 2 of 3" is read. */
@Composable
private fun StepProgress(done: Int, total: Int) {
    if (total < 2) return
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
        repeat(total) { i ->
            val fill by animateColorAsState(if (i < done) WM.colors.success else WM.colors.border, WMMotion.standard(), label = "segment")
            Box(Modifier.weight(1f).height(6.dp).background(fill, CircleShape))
        }
    }
}
