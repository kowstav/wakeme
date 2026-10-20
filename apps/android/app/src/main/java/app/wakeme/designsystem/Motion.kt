package app.wakeme.designsystem

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.delay

/**
 * Motion tokens (packages/design-tokens, docs/design/system.md §5). Effortless in daily use,
 * joyful only at the quack and on success, and absent from alarm, safety and escape controls.
 * Springs retarget from their current velocity, so every animation here can be interrupted.
 */
object WMMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Pressed controls: fast and nearly critically damped. */
    fun <T> press(): SpringSpec<T> = spring(WakeMeTokens.springPressDamping, WakeMeTokens.springPressStiffness)

    /** Selection, toggles, small layout changes. */
    fun <T> snappy(): SpringSpec<T> = spring(WakeMeTokens.springSnappyDamping, WakeMeTokens.springSnappyStiffness)

    /** Larger surfaces and progress: no overshoot. */
    fun <T> smooth(): SpringSpec<T> = spring(WakeMeTokens.springSmoothDamping, WakeMeTokens.springSmoothStiffness)

    /** Reserved for the quack and celebrations: one visible overshoot, never a loop. */
    fun <T> playful(): SpringSpec<T> = spring(WakeMeTokens.springPlayfulDamping, WakeMeTokens.springPlayfulStiffness)

    fun <T> quick(): TweenSpec<T> = tween(WakeMeTokens.motionQuickMs, easing = Standard)
    fun <T> standard(): TweenSpec<T> = tween(WakeMeTokens.motionStandardMs, easing = Standard)
    fun <T> enter(): TweenSpec<T> = tween(WakeMeTokens.motionEmphasizedMs, easing = EmphasizedDecelerate)
    fun <T> exit(): TweenSpec<T> = tween(WakeMeTokens.motionExitMs, easing = EmphasizedAccelerate)

    /** Text and status swaps: the old value fades out quickly, the new one fades in. No movement. */
    fun fadeThrough(): ContentTransform =
        fadeIn(tween(WakeMeTokens.motionStandardMs, delayMillis = WakeMeTokens.motionQuickMs / 2, easing = Standard)) togetherWith
            fadeOut(tween(WakeMeTokens.motionQuickMs, easing = Standard))
}

/**
 * Android's reduced-motion setting is "Remove animations" (animator duration scale 0). Compose
 * already finishes animations instantly then; this tells components to choose their still variant
 * (for example, the quack shows a duck mid-quack instead of a hop). Previews animate normally.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * The scale a pressed control animates to. Read it inside `graphicsLayer { }` so a press only
 * redraws the layer. With Remove animations on, controls keep their size and show colour only.
 */
@Composable
fun rememberPressScale(interactionSource: InteractionSource, enabled: Boolean = true): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    return animateFloatAsState(if (pressed && enabled && !reduced) WakeMeTokens.pressScale else 1f, WMMotion.press(), label = "press")
}

/**
 * The haptic vocabulary (tokens: haptics). The system haptics setting decides whether anything is
 * felt. Never used during an alarm or SOS beyond the system's own feedback.
 */
class WMHaptics(private val feedback: HapticFeedback) {
    fun selection() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun sent() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun failed() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun wrongAnswer() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun completed() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

@Composable
fun rememberHaptics(): WMHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { WMHaptics(feedback) }
}

/**
 * First-appearance entrance for the [index]th block of a screen: fade in and rise 12 dp,
 * [WakeMeTokens.staggerMs] after the previous block, capped at [WakeMeTokens.staggerMaxItems]
 * steps (about 200 ms in total). Runs once per composition; previews, screenshot tests and
 * Remove animations start at rest. Apply with `graphicsLayer { }` so it only redraws the layer.
 */
@Composable
fun rememberEntrance(index: Int): State<Float> {
    val still = rememberReducedMotion() || LocalInspectionMode.current
    val progress = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(WakeMeTokens.staggerMs.toLong() * index.coerceIn(0, WakeMeTokens.staggerMaxItems - 1))
            progress.animateTo(1f, WMMotion.enter())
        }
    }
    return progress.asState()
}
