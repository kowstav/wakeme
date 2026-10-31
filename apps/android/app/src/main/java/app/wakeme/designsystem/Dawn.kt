package app.wakeme.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where a time sits in the sky. Daytime runs 06:00 to 18:00 and night the other twelve hours, so
 * [fraction] goes 0 to 1 across one arc and a 06:15 alarm is a sun only just over the horizon.
 */
data class SkyPosition(val fraction: Float, val daytime: Boolean)

fun skyPosition(localTime: String): SkyPosition {
    val parts = localTime.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val minutes = (hour * 60 + minute).coerceIn(0, 1439)
    val daytime = minutes in 360 until 1080
    return SkyPosition((if (daytime) minutes - 360 else (minutes + 360) % 1440) / 720f, daytime)
}

/**
 * The signature: a thin horizon and a dotted path with the sun (a crescent moon at night) sitting
 * exactly where this time falls in the day. It replaces a repeated sun icon with something each
 * alarm owns. Decorative: the time beside it is what TalkBack reads. Never animates. At very large
 * text it steps aside, so the time keeps the width it needs.
 */
@Composable
fun DawnArc(
    localTime: String,
    modifier: Modifier = Modifier,
    width: Dp = WakeMeTokens.dawnArcWidth,
    height: Dp = WakeMeTokens.dawnArcHeight,
    sky: Color = WM.colors.heroFill,
) {
    if (LocalDensity.current.fontScale > 1.4f) return
    val c = WM.colors
    val position = skyPosition(localTime)
    Canvas(modifier.size(width, height).clearAndSetSemantics { }) {
        val line = 1.5.dp.toPx()
        val horizon = size.height - line / 2
        val radius = size.height * 0.15f
        val reachX = size.width / 2 - radius
        val reachY = horizon - radius - line
        drawArc(
            color = c.onHeroLine,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(radius, horizon - reachY),
            size = Size(reachX * 2, reachY * 2),
            style = Stroke(line, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx()))),
        )
        drawLine(c.onHeroLine, Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = line, cap = StrokeCap.Round)
        val angle = PI.toFloat() * (1 - position.fraction)
        val spot = Offset(size.width / 2 + reachX * cos(angle), horizon - reachY * sin(angle))
        // The horizon hides what hasn't risen yet.
        clipRect(bottom = horizon) {
            if (position.daytime) {
                drawCircle(c.sun, radius, spot)
            } else {
                drawCircle(c.moon, radius, spot)
                drawCircle(sky, radius, spot + Offset(radius * 0.55f, -radius * 0.2f))
            }
        }
    }
}

/**
 * The welcome's scene: the same sun, half risen behind lavender hills. Drawn in code so it fills
 * any window and follows the theme; the sky is left empty for the headline. Decorative.
 */
@Composable
fun DawnScene(modifier: Modifier = Modifier) {
    val c = WM.colors
    Canvas(modifier.clipToBounds().clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val pathReachX = w * 0.62f
        val pathReachY = h * 0.62f
        drawArc(
            color = c.hillFar,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.5f - pathReachX, h * 0.74f - pathReachY),
            size = Size(pathReachX * 2, pathReachY * 2),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx()))),
        )
        drawCircle(c.sun, min(w, h) * 0.3f, Offset(w * 0.68f, h * 0.56f))
        hill(c.hillFar, 0.60f, 0.22f to 0.42f, 0.40f to 0.46f, 0.56f to 0.60f, 0.72f to 0.74f, 0.86f to 0.56f, 0.52f)
        hill(c.hillNear, 0.70f, 0.20f to 0.82f, 0.38f to 0.86f, 0.54f to 0.76f, 0.68f to 0.66f, 0.84f to 0.66f, 0.76f)
        hill(c.hillFront, 0.92f, 0.24f to 0.78f, 0.46f to 0.84f, 0.62f to 0.92f, 0.76f to 0.98f, 0.90f to 0.92f, 0.84f)
    }
}

/** One hill across the width: two curves meeting at [mid], given as fractions of the canvas. */
private fun DrawScope.hill(
    color: Color,
    start: Float,
    c1: Pair<Float, Float>,
    c2: Pair<Float, Float>,
    mid: Pair<Float, Float>,
    c3: Pair<Float, Float>,
    c4: Pair<Float, Float>,
    end: Float,
) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * start)
        cubicTo(w * c1.first, h * c1.second, w * c2.first, h * c2.second, w * mid.first, h * mid.second)
        cubicTo(w * c3.first, h * c3.second, w * c4.first, h * c4.second, w, h * end)
        lineTo(w, h)
        close()
    }
    drawPath(path, color)
}
