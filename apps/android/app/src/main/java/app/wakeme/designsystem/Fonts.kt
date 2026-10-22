package app.wakeme.designsystem

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Roboto Condensed (a system font on every Android phone) for the display role: Bloom's few hero
 * headlines are set condensed, heavy and in capitals, like the reference apps' display faces,
 * without bundling a font. A typeface loaded by name keeps its own weight (Compose does not pick a
 * cut for it), so the weight is set here: 800, the role's weight, from Roboto's variable weight axis
 * (API 28+; minSdk is 31). The regular cut read thin next to iOS's SF Pro Condensed Black.
 * Kept apart from Theme.kt so the generated `WakeMeType` can read it without a
 * class-initialisation cycle.
 */
val CondensedFontFamily: FontFamily = FontFamily(
    Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false),
)
