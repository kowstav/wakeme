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

private fun scheme(c: WakeMeColors, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.textOnAccent, background = c.surface, onBackground = c.textPrimary,
            surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceRaised, error = c.danger, onError = c.textOnDanger, outline = c.border, secondary = c.info,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.textOnAccent, background = c.surface, onBackground = c.textPrimary,
            surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceSunken, onSurfaceVariant = c.textSecondary,
            surfaceContainer = c.surfaceRaised, error = c.dangerFill, onError = c.textOnDanger, outline = c.border, secondary = c.info,
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
