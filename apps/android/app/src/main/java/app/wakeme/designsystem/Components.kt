package app.wakeme.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One clear primary action per screen; minimum 48 dp targets. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = WakeMeTokens.minTouchTarget) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = height),
        shape = RoundedCornerShape(WakeMeTokens.radiusMd),
        colors = ButtonDefaults.buttonColors(containerColor = WM.colors.accent, contentColor = WM.colors.textOnAccent),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.spaceLg, vertical = WakeMeTokens.spaceSm),
    ) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.minTouchTarget),
        shape = RoundedCornerShape(WakeMeTokens.radiusMd),
        border = BorderStroke(1.dp, if (enabled) WM.colors.border else WM.colors.border.copy(alpha = 0.5f)),
    ) {
        // A disabled button must not look tappable: Material's 38 % for disabled content.
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) WM.colors.textPrimary else WM.colors.textPrimary.copy(alpha = 0.38f), textAlign = TextAlign.Center)
    }
}

/** Destructive and SOS actions only. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.minTouchTarget),
        shape = RoundedCornerShape(WakeMeTokens.radiusMd),
        colors = ButtonDefaults.buttonColors(containerColor = WM.colors.dangerFill, contentColor = WM.colors.textOnDanger),
    ) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = WakeMeTokens.minTouchTarget)) {
        Text(text, color = WM.colors.accentText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun WMCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WakeMeTokens.radiusLg),
        colors = CardDefaults.cardColors(containerColor = WM.colors.surfaceRaised),
        border = BorderStroke(1.dp, WM.colors.border),
    ) { Column(Modifier.padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm), content = content) }
}

enum class Tone { Neutral, Info, Success, Warning, Danger }

/** Status is always conveyed in words, never by colour alone. */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = WM.colors
    val color = when (tone) {
        Tone.Neutral -> c.textSecondary
        Tone.Info -> c.info
        Tone.Success -> c.success
        Tone.Warning -> c.warning
        Tone.Danger -> c.danger
    }
    Row(
        modifier
            .background(c.surfaceSunken, RoundedCornerShape(WakeMeTokens.radiusPill))
            .padding(horizontal = WakeMeTokens.spaceSm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = WM.colors.textPrimary, modifier = modifier.semantics { this.contentDescription = text })
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = WM.colors.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier)
}

@Composable
fun Gap(height: Dp = WakeMeTokens.spaceMd) = Spacer(Modifier.height(height))

/** A toggle row whose whole surface is the touch target and reads as a switch to TalkBack. */
@Composable
fun SettingRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = WakeMeTokens.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = WM.colors.textPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WM.colors.textSecondary) }
        }
        Spacer(Modifier.width(WakeMeTokens.spaceSm))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

/** A number with − and + buttons, each a full 48 dp target with its own spoken label. */
@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = WM.colors.textPrimary)
        StepButton("−", "Fewer", value > min) { onChange((value - 1).coerceAtLeast(min)) }
        StepButton("+", "More", value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(glyph: String, spoken: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(WakeMeTokens.minTouchTarget).semantics { contentDescription = spoken },
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) WM.colors.border else WM.colors.border.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(0.dp),
    ) { Text(glyph, style = MaterialTheme.typography.titleLarge, color = if (enabled) WM.colors.textPrimary else WM.colors.textPrimary.copy(alpha = 0.38f)) }
}
