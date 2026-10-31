@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package app.wakeme.designsystem

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.wakeme.R

/** Bundled Manrope, OFL 1.1. System fallback covers scripts outside its glyph set. */
val BrandFontFamily = FontFamily(
    *listOf(400, 500, 600, 700).map { weight ->
        Font(R.font.manrope, weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    }.toTypedArray(),
)
