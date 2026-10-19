package app.wakeme.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalWakeMeColors = staticCompositionLocalOf { WakeMeTokens.Light }

// Material uses `primary` as the text colour of text buttons, focused field labels and the
// checked switch track, so it maps to accentText (contrast-checked on both surfaces), not to
// the accent fill; PrimaryButton sets the accent fill itself. Selected chips and the navigation
// indicator use secondaryContainer: the accent fill with its own text colour, instead of
// Material's default lavender, which isn't part of the design system; so does the unchecked
// switch track (surfaceContainerHighest).
private fun scheme(c: WakeMeColors, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = c.accentText, onPrimary = c.surfaceRaised, background = c.surface, onBackground = c.textPrimary,
            surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceRaised, error = c.danger, onError = c.textOnDanger, outline = c.border, secondary = c.info,
            secondaryContainer = c.accent, onSecondaryContainer = c.textOnAccent, surfaceContainerHighest = c.surfaceSunken,
        )
    } else {
        lightColorScheme(
            primary = c.accentText, onPrimary = c.surfaceRaised, background = c.surface, onBackground = c.textPrimary,
            surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceRaised, error = c.dangerFill, onError = c.textOnDanger, outline = c.border, secondary = c.info,
            secondaryContainer = c.accent, onSecondaryContainer = c.textOnAccent, surfaceContainerHighest = c.surfaceSunken,
        )
    }

/** Warm daylight / quiet night. System fonts so Dynamic Type-style scaling is honoured. */
@Composable
fun WakeMeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) WakeMeTokens.Dark else WakeMeTokens.Light
    CompositionLocalProvider(LocalWakeMeColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors, dark), content = content)
    }
}

object WM {
    val colors: WakeMeColors @Composable get() = LocalWakeMeColors.current
}
