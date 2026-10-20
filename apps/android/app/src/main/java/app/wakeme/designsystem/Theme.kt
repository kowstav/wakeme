package app.wakeme.designsystem

import android.app.UiModeManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val LocalWakeMeColors = staticCompositionLocalOf { WakeMeTokens.Light }

/** Whether the palette in use is the dark one (the alarm always is). Avatars and shadows follow it. */
val LocalWakeMeDark = staticCompositionLocalOf { false }

// Material uses `primary` as the text colour of text buttons, focused field labels and the
// checked switch track, so it maps to accentText (contrast-checked on every surface), not to
// the accent fill; PrimaryButton sets the accent fill itself. Selected chips and the navigation
// indicator use secondaryContainer: the butter accentContainer with its own text colour, instead
// of Material's default lavender. `outline` (≥ 3:1, WCAG 1.4.11) draws text-field and control
// boundaries; `outlineVariant` is the decorative hairline for dividers. Dialogs and sheets use the
// overlay surface; the unchecked switch track uses the sunken surface. Dark `onError` is ink, since
// the dark error colour is a light salmon.
private fun scheme(c: WakeMeColors, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = c.accentText, onPrimary = c.textOnAccent, primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
            secondary = c.info, onSecondary = c.textOnAccent, secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            tertiary = c.success, tertiaryContainer = c.successContainer,
            background = c.surface, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary, surfaceTint = c.surface,
            surfaceContainerLowest = c.surfaceRaised, surfaceContainerLow = c.surfaceRaised, surfaceContainer = c.surfaceRaised,
            surfaceContainerHigh = c.surfaceOverlay, surfaceContainerHighest = c.surfaceSunken,
            error = c.danger, onError = c.textOnAccent, errorContainer = c.dangerContainer, onErrorContainer = c.textPrimary,
            outline = c.outline, outlineVariant = c.border, scrim = c.surfaceSunken,
        )
    } else {
        lightColorScheme(
            primary = c.accentText, onPrimary = c.surfaceRaised, primaryContainer = c.accentContainer, onPrimaryContainer = c.onAccentContainer,
            secondary = c.info, onSecondary = c.surfaceRaised, secondaryContainer = c.accentContainer, onSecondaryContainer = c.onAccentContainer,
            tertiary = c.success, tertiaryContainer = c.successContainer,
            background = c.surface, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary, surfaceTint = c.surface,
            surfaceContainerLowest = c.surfaceRaised, surfaceContainerLow = c.surfaceRaised, surfaceContainer = c.surfaceRaised,
            surfaceContainerHigh = c.surfaceOverlay, surfaceContainerHighest = c.surfaceSunken,
            error = c.dangerFill, onError = c.textOnDanger, errorContainer = c.dangerContainer, onErrorContainer = c.textPrimary,
            outline = c.outline, outlineVariant = c.border, scrim = c.textPrimary,
        )
    }

/** Text fields use extraSmall, chips small, cards large, dialogs and sheets extraLarge. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(WakeMeTokens.radiusSm),
    small = RoundedCornerShape(WakeMeTokens.radiusMd),
    medium = RoundedCornerShape(WakeMeTokens.radiusMd),
    large = RoundedCornerShape(WakeMeTokens.radiusLg),
    extraLarge = RoundedCornerShape(WakeMeTokens.radiusXl),
)

/**
 * The system font (Roboto) on Wake Me's semantic roles (tokens: type.roles), mapped onto Material's
 * slots so Material components pick them up: dialogs, text fields, chips and the navigation bar.
 * Sizes are sp and follow the person's font size; figures are tabular everywhere, so 07:00 → 07:01
 * doesn't shift sideways.
 *
 * | Material slot | Role | Size / line | Weight |
 * |---|---|---|---|
 * | displayLarge | alarmTime | 76 / 80 | Bold |
 * | displayMedium | timeDisplay | 56 / 60 | SemiBold |
 * | displaySmall | timeInline | 32 / 36 | SemiBold |
 * | headlineLarge | hero | 32 / 40 | Bold |
 * | headlineMedium | screenTitle | 28 / 36 | Bold |
 * | headlineSmall | prominent (dialog titles too) | 24 / 32 | SemiBold |
 * | titleLarge | section | 22 / 28 | SemiBold |
 * | titleMedium | cardTitle, button labels | 17 / 24 | SemiBold |
 * | titleSmall | fieldLabel | 14 / 20 | SemiBold |
 * | bodyLarge | body (text fields too) | 16 / 24 | Regular |
 * | bodyMedium | supporting (dialog text too) | 14 / 20 | Regular |
 * | labelLarge | label (pills, chips) | 14 / 20 | SemiBold |
 * | labelMedium | caption (navigation labels too) | 12 / 16 | Medium |
 */
private val typography: Typography = Typography().let { t ->
    t.copy(
        displayLarge = WakeMeType.alarmTime,
        displayMedium = WakeMeType.timeDisplay,
        displaySmall = WakeMeType.timeInline,
        headlineLarge = WakeMeType.hero,
        headlineMedium = WakeMeType.screenTitle,
        headlineSmall = WakeMeType.prominent,
        titleLarge = WakeMeType.section,
        titleMedium = WakeMeType.cardTitle,
        titleSmall = WakeMeType.fieldLabel,
        bodyLarge = WakeMeType.body,
        bodyMedium = WakeMeType.supporting,
        bodySmall = t.bodySmall.copy(fontFeatureSettings = "tnum"),
        labelLarge = WakeMeType.label,
        labelMedium = WakeMeType.caption,
        labelSmall = t.labelSmall.copy(fontFeatureSettings = "tnum"),
    )
}

/**
 * Lets an already-large style grow with the font-size setting only up to [maxScale]. Big numerals
 * stay legible at every setting, but a 76 sp clock at 2× would no longer fit a phone.
 */
@Composable
fun TextStyle.withCappedScale(maxScale: Float): TextStyle {
    val scale = LocalDensity.current.fontScale
    if (scale <= maxScale) return this
    val factor = maxScale / scale
    return copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
}

/** The routine time on the detail and in the editor (tokens: type.roles.timeDisplay). */
val TimeDisplayStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displayMedium.withCappedScale(WakeMeType.timeDisplayMaxScale)

/** The alarm clock face (tokens: type.roles.alarmTime). */
val AlarmTimeStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displayLarge.withCappedScale(WakeMeType.alarmTimeMaxScale)

/**
 * Android 14+ lets people raise contrast (Settings › Accessibility › Colour and motion › Contrast).
 * At medium or high, Wake Me switches to its increased-contrast palette: stronger secondary text,
 * outlines and accent text. Earlier versions, previews and screenshot tests use the normal palette.
 */
@Composable
fun rememberHighContrast(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || LocalInspectionMode.current) return false
    val context = LocalContext.current
    val modes = remember(context) { context.getSystemService(UiModeManager::class.java) } ?: return false
    var contrast by remember(modes) { mutableFloatStateOf(runCatching { modes.contrast }.getOrDefault(0f)) }
    DisposableEffect(modes) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        modes.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { modes.removeContrastChangeListener(listener) }
    }
    return contrast >= 0.5f
}

/** Daybreak: warm daylight / quiet night (docs/design/system.md). System fonts so font scaling is honoured. */
@Composable
fun WakeMeTheme(dark: Boolean = isSystemInDarkTheme(), highContrast: Boolean = rememberHighContrast(), content: @Composable () -> Unit) {
    val colors = when {
        dark && highContrast -> WakeMeTokens.DarkHighContrast
        dark -> WakeMeTokens.Dark
        highContrast -> WakeMeTokens.LightHighContrast
        else -> WakeMeTokens.Light
    }
    CompositionLocalProvider(LocalWakeMeColors provides colors, LocalWakeMeDark provides dark) {
        MaterialTheme(colorScheme = scheme(colors, dark), shapes = shapes, typography = typography, content = content)
    }
}

object WM {
    val colors: WakeMeColors @Composable get() = LocalWakeMeColors.current
    val dark: Boolean @Composable get() = LocalWakeMeDark.current
}
