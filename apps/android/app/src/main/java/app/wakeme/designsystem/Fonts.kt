package app.wakeme.designsystem

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Roboto Condensed (a system font on every Android phone) for the display role: Bloom's few hero
 * headlines are set condensed and in capitals, like the reference apps' display faces, without
 * bundling a font. Compose picks the bold cut for the role's weight. Kept apart from Theme.kt so
 * the generated `WakeMeType` can read it without a class-initialisation cycle.
 */
val CondensedFontFamily: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
