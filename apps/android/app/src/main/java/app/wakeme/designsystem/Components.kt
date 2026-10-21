package app.wakeme.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wakeme.core.DeliveryTrail
import app.wakeme.core.TrailState

/**
 * One clear primary action per screen: the yellow pill, 52 dp tall with a 17 sp label. Alarm
 * controls (72 dp) carry a 22 sp label so the proportion holds for a sleepy reader. Pressing
 * deepens the fill and settles the button slightly; with Remove animations on, only the colour
 * changes.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = WakeMeTokens.buttonHeight,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = WM.colors
    val labelStyle = if (height >= WakeMeTokens.alarmButtonHeight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = height).graphicsLayer { val s = scale.value; scaleX = s; scaleY = s },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) c.accentPressed else c.accent,
            contentColor = c.textOnAccent,
            disabledContainerColor = c.textPrimary.copy(alpha = 0.12f),
            disabledContentColor = c.textPrimary.copy(alpha = WakeMeTokens.opacityDisabled),
        ),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
        interactionSource = interaction,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(WakeMeTokens.spaceXs))
        }
        Text(text, style = labelStyle, textAlign = TextAlign.Center)
    }
}

/**
 * Outlined with `outline` (≥ 3:1), so the button's edge is visible, not just its label. Same
 * height and label as [PrimaryButton]: stacked or paired actions line up, and the fill alone says
 * which one leads.
 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = WakeMeTokens.buttonHeight) {
    val c = WM.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = height).graphicsLayer { val s = scale.value; scaleX = s; scaleY = s },
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (pressed) c.surfaceSunken else Color.Transparent),
        border = BorderStroke(1.dp, if (enabled) c.outline else c.border),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
        interactionSource = interaction,
    ) {
        // A disabled button must not look tappable: Material's 38 % for disabled content.
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.textPrimary else c.textPrimary.copy(alpha = WakeMeTokens.opacityDisabled), textAlign = TextAlign.Center)
    }
}

/** Destructive and SOS actions only. Never animated: it must look and act the same every time. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.buttonHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = WM.colors.dangerFill, contentColor = WM.colors.textOnDanger),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
    ) { Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}

/**
 * A tertiary action in the accent text colour. No side padding, so its label lines up with the
 * text it sits under (Material's 12 dp inset made these look indented); still a 48 dp target.
 * Material's pill shape clipped the first letters once the label wrapped at large text ("Use the
 * simple check-in instead" in the run 76 previews); corners no larger than the vertical padding
 * stay clear of the label.
 */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = WakeMeTokens.minTouchTarget, minHeight = WakeMeTokens.minTouchTarget),
        shape = RoundedCornerShape(WakeMeTokens.radiusSm),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = WakeMeTokens.spaceXs),
    ) {
        Text(text, color = WM.colors.accentText.copy(alpha = if (enabled) 1f else WakeMeTokens.opacityDisabled), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * A raised card: hairline border plus one soft ink shadow by day, a lighter tonal surface by night.
 * With [onClick] the whole card is one target with press feedback; [onClickLabel] tells TalkBack
 * what a tap does ("Open Maya").
 */
@Composable
fun WMCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    container: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = WM.colors
    val shape = RoundedCornerShape(WakeMeTokens.radiusLg)
    val lifted = if (WM.dark) Modifier else Modifier.shadow(WakeMeTokens.elevationCard, shape, clip = false, ambientColor = c.textPrimary.copy(alpha = 0.10f), spotColor = c.textPrimary.copy(alpha = 0.10f))
    val colors = CardDefaults.cardColors(containerColor = container ?: c.surfaceRaised)
    val border = BorderStroke(1.dp, c.border)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm), content = content)
    }
    if (onClick == null) {
        Card(modifier = modifier.fillMaxWidth().then(lifted), shape = shape, colors = colors, border = border, content = inner)
    } else {
        val interaction = remember { MutableInteractionSource() }
        val scale = rememberPressScale(interaction)
        Card(
            modifier = modifier.fillMaxWidth().graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }.then(lifted)
                .clip(shape)
                .clickable(interactionSource = interaction, indication = ripple(), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
            shape = shape, colors = colors, border = border, content = inner,
        )
    }
}

enum class Tone { Neutral, Info, Success, Warning, Danger }

/**
 * Status is always words plus a dot, never colour alone. The tinted pill makes the tone visible at
 * a glance; a change crossfades (no movement). With [announce], TalkBack reads changes politely,
 * for statuses that change while the person waits (delivery, readiness).
 */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, announce: Boolean = false) {
    val c = WM.colors
    val (dot, fill) = when (tone) {
        Tone.Neutral -> c.textSecondary to c.textPrimary.copy(alpha = 0.08f)
        Tone.Info -> c.info to c.infoContainer
        Tone.Success -> c.success to c.successContainer
        Tone.Warning -> c.warning to c.warningContainer
        Tone.Danger -> c.danger to c.dangerContainer
    }
    val fillColor by animateColorAsState(fill, WMMotion.standard(), label = "pillFill")
    val dotColor by animateColorAsState(dot, WMMotion.standard(), label = "pillDot")
    Row(
        modifier
            .background(fillColor, RoundedCornerShape(WakeMeTokens.radiusPill))
            .padding(horizontal = WakeMeTokens.spaceSm, vertical = 6.dp)
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.size(6.dp))
        AnimatedContent(targetState = text, transitionSpec = { WMMotion.fadeThrough() }, label = "pillText") { t ->
            Text(t, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
        }
    }
}

/**
 * A header between groups of content, outside cards (22 sp SemiBold). It carries extra space above
 * so it belongs to what follows, not to what came before.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary, modifier = modifier.padding(top = WakeMeTokens.layoutSectionTopExtra).semantics { heading() })
}

/** The title of a card or list row (17 sp SemiBold), the same size as button labels. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = modifier.semantics { heading() })
}

/** A secondary line under a title: metadata, who suggested it, a short explanation (14 sp). */
@Composable
fun Supporting(text: String, modifier: Modifier = Modifier, color: Color = WM.colors.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

/**
 * A labelled group of form controls: the label sits 8 dp above its controls, so it reads as theirs.
 * Separate groups with the screen's larger spacing.
 */
@Composable
fun FieldGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.layoutFieldLabelGap)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = WM.colors.textSecondary, modifier = Modifier.semantics { heading() })
        content()
    }
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
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = WakeMeTokens.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { haptics.toggle(it); onChange(it) }),
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
    val haptics = rememberHaptics()
    val c = WM.colors
    val tint = if (enabled) c.textPrimary else c.textPrimary.copy(alpha = WakeMeTokens.opacityDisabled)
    Box(
        Modifier
            .size(WakeMeTokens.minTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = spoken) { haptics.selection(); onClick() }
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(WakeMeTokens.iconButtonVisual).border(1.dp, if (enabled) c.outline else c.border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (glyph == "+") Icons.Filled.Add else Icons.Filled.Remove, contentDescription = null, tint = tint, modifier = Modifier.size(WakeMeTokens.iconSizeSmall))
        }
    }
}

/**
 * A single choice among a few (sounds, ring length, presets). Selected chips are butter with a
 * darker edge and a check mark, so the state never depends on colour alone. A light selection
 * haptic confirms the change.
 */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = WM.colors
    val haptics = rememberHaptics()
    FilterChip(
        selected = selected,
        onClick = { haptics.selection(); onClick() },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (selected) { { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) } } else null,
        shape = RoundedCornerShape(WakeMeTokens.radiusMd),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = c.textPrimary,
            selectedContainerColor = c.accentContainer,
            selectedLabelColor = c.onAccentContainer,
            selectedLeadingIconColor = c.onAccentContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = c.outline, selectedBorderColor = c.accentText,
            borderWidth = 1.dp, selectedBorderWidth = 1.5.dp,
        ),
    )
}

/**
 * A friend's monogram on one of six equal-lightness tints, chosen from a stable key so the same
 * friend always has the same colour. Decorative: the name beside it is what TalkBack reads.
 */
@Composable
fun FriendAvatar(name: String, key: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val tints = if (WM.dark) WakeMeTokens.AvatarDark else WakeMeTokens.AvatarLight
    val tint = tints[Math.floorMod(key.hashCode(), tints.size)]
    val trimmed = name.trim()
    val initial = if (trimmed.isEmpty()) "?" else String(Character.toChars(trimmed.codePointAt(0))).uppercase()
    // Sized to the circle rather than the font scale: the name beside it scales instead.
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(modifier.size(size).background(tint, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(initial, fontSize = fontSize, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary, maxLines = 1)
    }
}

/**
 * Sent → reached their phone → seen (quack) / rang (wake alarm). Steps fill only for evidence the
 * server reported ([DeliveryTrail], shared fixture), and the fill animates only when that status
 * changes, never on a timer. Visual only: the literal status pill beside it is what TalkBack reads
 * and announces, so nothing is said twice.
 */
@Composable
fun DeliveryTrailView(displayStatus: String, alarm: Boolean, modifier: Modifier = Modifier) {
    val c = WM.colors
    val trail = DeliveryTrail.of(displayStatus, alarm)
    val fill by animateFloatAsState(trail.reached.toFloat(), WMMotion.smooth(), label = "trail")
    val labels = listOf("Sent", "Reached their phone", if (alarm) "Rang on their phone" else "They saw it")
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs),
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val r = 7.dp.toPx()
            val stroke = 2.dp.toPx()
            val y = size.height / 2
            val xs = listOf(r, size.width / 2, size.width - r)
            // Track, then the reached part. `fill` 1 ends at the first node, 3 at the last.
            drawLine(c.border, Offset(xs[0], y), Offset(xs[2], y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            if (fill > 1f) {
                val f = (fill - 1f).coerceIn(0f, 2f)
                val end = if (f <= 1f) xs[0] + (xs[1] - xs[0]) * f else xs[1] + (xs[2] - xs[1]) * (f - 1f)
                drawLine(c.success, Offset(xs[0], y), Offset(end, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            xs.forEachIndexed { i, x ->
                val reached = i < trail.reached
                val failedHere = i == trail.reached && trail.state == TrailState.PROBLEM
                when {
                    reached -> drawCircle(c.success, r, Offset(x, y))
                    failedHere -> {
                        drawCircle(c.surfaceRaised, r, Offset(x, y))
                        drawCircle(c.warning, r - stroke / 2, Offset(x, y), style = Stroke(stroke))
                        val d = r * 0.4f
                        drawLine(c.warning, Offset(x - d, y - d), Offset(x + d, y + d), stroke, StrokeCap.Round)
                        drawLine(c.warning, Offset(x - d, y + d), Offset(x + d, y - d), stroke, StrokeCap.Round)
                    }
                    else -> {
                        drawCircle(c.surfaceRaised, r, Offset(x, y))
                        drawCircle(c.outline, r - stroke / 2, Offset(x, y), style = Stroke(stroke))
                    }
                }
            }
        }
        // The pill above says the same thing in words; at very large text the step labels would
        // stack three lines deep, so the trail stays visual-only there.
        if (LocalDensity.current.fontScale < 1.5f) Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i < trail.reached) c.textPrimary else c.textSecondary,
                    fontWeight = if (i == trail.reached - 1) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                )
            }
        }
    }
}

/**
 * A visible way back on detail and editor screens (system Back and predictive back still work).
 * 48 dp target, spoken as "Back".
 */
@Composable
fun DetailTopBar(onBack: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WM.colors.textPrimary) }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A pushed screen: a pinned back bar (system Back and predictive back still work) above scrolling
 * content with the standard margins and rhythm. Without [onBack] (a top-level use) it is just the
 * scrolling column.
 */
@Composable
fun DetailScreen(onBack: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        if (onBack != null) DetailTopBar(onBack = onBack, modifier = Modifier.padding(horizontal = WakeMeTokens.spaceXxs), title = title)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WakeMeTokens.screenMargin)
                .padding(top = if (onBack == null) WakeMeTokens.spaceMd else 0.dp, bottom = WakeMeTokens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
            content = content,
        )
    }
}

/** A calm empty state: the duck, what belongs here, and how to add the first one. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    WMCard(modifier, container = WM.colors.surfaceRaised) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            DuckMark(size = 48.dp)
            Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Body(body)
        action?.invoke()
    }
}
