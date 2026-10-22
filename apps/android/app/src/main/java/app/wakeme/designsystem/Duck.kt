package app.wakeme.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Wake Me duck, drawn from the same 64-unit geometry as iOS `DuckMark` and the design
 * directions page (docs/design/system.md §6). Original artwork instead of the 🦆 emoji, which
 * looks different on every phone and can't open its beak. Always decorative: hidden from
 * TalkBack, because the words beside it carry the meaning.
 */
@Composable
fun DuckMark(modifier: Modifier = Modifier, size: Dp = 48.dp, beakOpen: Float = 0f) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) { drawDuck(WakeMeTokens.illustrationDuckBody, WakeMeTokens.illustrationDuckWing, beakOpen) }
}

/**
 * The signature quack: the duck hops once, opens its beak, and three sound marks ripple out and
 * fade, in [WakeMeTokens.motionQuackMs]. It replays only when [tick] changes (a send the server
 * accepted), and it says nothing about delivery: the trail below it does. With Remove animations
 * on, it is a still duck mid-quack. At rest (tick 0) it is a calm duck with a closed beak.
 */
@Composable
fun QuackBurst(tick: Int, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    val c = WM.colors
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(tick, reduced) {
        if (tick > 0 && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(WakeMeTokens.motionQuackMs, easing = LinearEasing))
        }
    }
    val still = reduced && tick > 0
    // 80 × 64 units: the duck plus room for the sound marks to its right.
    Canvas(modifier.size(width = height * 1.25f, height = height).clearAndSetSemantics { }) {
        val p = progress.value // read only while drawing: the animation never recomposes
        val u = size.height / 64f
        scale(u, u, pivot = Offset.Zero) {
            val hop = if (still) 0f else hopAt(p)
            translate(top = -5f * hop) {
                rotate(-7f * hop, pivot = Offset(32f, 46f)) {
                    drawDuckUnits(WakeMeTokens.illustrationDuckBody, WakeMeTokens.illustrationDuckWing, if (still) 0.7f else beakAt(p))
                }
            }
            for (i in 0..2) {
                val alpha = if (still) 1f - 0.25f * i else waveAlpha(p, i)
                if (alpha <= 0f) continue
                val r = (6f + 4.5f * i) * (if (still) 1f else waveGrowth(p, i))
                drawArc(
                    color = c.accentText.copy(alpha = alpha),
                    startAngle = -38f, sweepAngle = 76f, useCenter = false,
                    topLeft = Offset(62f - r, 24f - r), size = Size(2 * r, 2 * r),
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun hopAt(p: Float): Float = if (p < 0.45f) sin(PI.toFloat() * p / 0.45f) else 0f

private fun beakAt(p: Float): Float = when {
    p < 0.08f -> 0f
    p < 0.20f -> (p - 0.08f) / 0.12f
    p < 0.38f -> 1f
    p < 0.52f -> 1f - (p - 0.38f) / 0.14f
    else -> 0f
}

private fun waveStart(i: Int) = 0.12f + 0.1f * i
private fun waveGrowth(p: Float, i: Int) = 0.75f + 0.25f * ((p - waveStart(i)) / 0.5f).coerceIn(0f, 1f)
private fun waveAlpha(p: Float, i: Int): Float {
    val t = (p - waveStart(i)) / 0.5f
    return when {
        t <= 0f || t >= 1f -> 0f
        t < 0.3f -> t / 0.3f
        else -> 1f - (t - 0.3f) / 0.7f
    }
}

/** Draws the duck into the canvas, scaled from its 64-unit box. */
fun DrawScope.drawDuck(body: Color, wing: Color, beakOpen: Float) {
    val u = size.minDimension / 64f
    scale(u, u, pivot = Offset.Zero) { drawDuckUnits(body, wing, beakOpen) }
}

private fun DrawScope.drawDuckUnits(body: Color, wing: Color, beakOpen: Float) {
    drawPath(DuckPaths.tail, body)
    drawOval(body, topLeft = Offset(9f, 28f), size = Size(42f, 28f))
    drawCircle(body, radius = 12f, center = Offset(42f, 22f))
    drawPath(DuckPaths.wing, wing)
    // The lower beak hinges at the corner of the mouth and swings down as it opens.
    rotate(24f * beakOpen.coerceIn(0f, 1f), pivot = Offset(51f, 24.5f)) { drawPath(DuckPaths.lowerBeak, WakeMeTokens.illustrationDuckBeakShade) }
    drawPath(DuckPaths.upperBeak, WakeMeTokens.illustrationDuckBeak)
    drawCircle(WakeMeTokens.illustrationDuckEye, radius = 2.3f, center = Offset(45f, 19f))
    drawCircle(WakeMeTokens.illustrationDuckEyeHighlight, radius = 0.75f, center = Offset(45.8f, 18.2f))
}

private object DuckPaths {
    val tail = Path().apply { moveTo(12f, 37f); lineTo(5f, 25f); quadraticTo(13f, 27f, 19f, 32f); close() }
    val wing = Path().apply { moveTo(20f, 40f); quadraticTo(30f, 34f, 40f, 40f); quadraticTo(32f, 48f, 22f, 46f); close() }
    val upperBeak = Path().apply { moveTo(51f, 18.5f); quadraticTo(60f, 18f, 62.5f, 22f); quadraticTo(58f, 24.5f, 51f, 24.5f); close() }
    val lowerBeak = Path().apply { moveTo(51f, 24.5f); quadraticTo(57f, 24.5f, 60.5f, 26f); quadraticTo(56f, 28.5f, 51f, 28f); close() }
}
