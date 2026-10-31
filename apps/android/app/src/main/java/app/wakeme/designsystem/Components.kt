package app.wakeme.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.unit.sp
import app.wakeme.core.DeliveryTrail
import app.wakeme.core.TrailState

/**
 * True inside a [WMCard] or tile. Quiet controls (secondary buttons, icon buttons, unselected
 * chips) sit on white there by day, and on the page's neutral grey everywhere else, so they never
 * vanish into the card behind them.
 */
val LocalOnCard = staticCompositionLocalOf { false }

/** The fill of a quiet control: card grey on the page, white on a card by day, a lighter tone by night. */
@Composable
internal fun quietFill(): Color {
    val c = WM.colors
    return if (!LocalOnCard.current) c.surfaceNeutral else if (WM.dark) c.surfacePressed else c.surfaceRaised
}

/** [fill] while pressed: 8 % of the text colour over it, so every surface darkens (or lightens at night) the same way. */
@Composable
private fun pressedOf(fill: Color): Color = lerp(fill, WM.colors.textPrimary, 0.08f)

/**
 * A Bloom icon (packages/design-tokens/icons), tinted. Decorative unless [contentDescription] is
 * given: the words next to an icon are what TalkBack reads.
 */
@Composable
fun WMIconView(icon: WMIcon, modifier: Modifier = Modifier, tint: Color = WM.colors.textPrimary, size: Dp = WakeMeTokens.iconSize, contentDescription: String? = null) {
    Icon(painterResource(icon.res), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** An icon on a petal-coloured circle: rows, settings and tiles use these to tell things apart at a glance. */
@Composable
fun IconChip(icon: WMIcon, family: WakeMeFamily, modifier: Modifier = Modifier, size: Dp = WakeMeTokens.iconChip) {
    Box(modifier.size(size).background(family.fill, CircleShape), contentAlignment = Alignment.Center) {
        WMIconView(icon, tint = family.ink, size = size * 0.55f)
    }
}

/**
 * One clear primary action per screen: the lime pill with forest ink, 56 dp tall with a 17 sp
 * label (Bloom keeps green for actions). An [icon] leads the label where it adds recognition
 * (send, invite, add); the words stay. Alarm controls (72 dp) carry a 22 sp label. Pressing deepens
 * the fill and settles the button slightly; with Remove animations on, only the colour changes.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = WakeMeTokens.buttonHeight,
    icon: WMIcon? = null,
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
            // On a card the card's own grey would hide the button's shape: use the quiet fill there.
            disabledContainerColor = quietFill(),
            disabledContentColor = c.textPrimary.copy(alpha = WakeMeTokens.opacityDisabled),
        ),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
        interactionSource = interaction,
    ) {
        ButtonLabel(text, labelStyle, icon, leading)
    }
}

@Composable
private fun ButtonLabel(text: String, style: androidx.compose.ui.text.TextStyle, icon: WMIcon?, leading: (@Composable () -> Unit)? = null) {
    when {
        leading != null -> { leading(); Spacer(Modifier.width(WakeMeTokens.spaceXs)) }
        icon != null -> { WMIconView(icon, tint = LocalContentColor.current, size = WakeMeTokens.iconSizeSmall); Spacer(Modifier.width(WakeMeTokens.spaceXs)) }
    }
    Text(text, style = style, textAlign = TextAlign.Center)
}

/**
 * A filled neutral pill (the reference apps' "Sign in with Apple" button): grey on the page, white
 * on a card. Same height and label as [PrimaryButton], so stacked or paired actions line up and the
 * lime fill alone says which one leads. Its words identify it, so it needs no outline (WCAG 1.4.11
 * applies to boundaries a control needs to be recognised).
 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = WakeMeTokens.buttonHeight, icon: WMIcon? = null) {
    val c = WM.colors
    val fill = quietFill()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = height).graphicsLayer { val s = scale.value; scaleX = s; scaleY = s },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) pressedOf(fill) else fill,
            contentColor = c.textPrimary,
            // A disabled button must not look tappable: Material's 38 % for disabled content.
            disabledContainerColor = fill.copy(alpha = 0.6f),
            disabledContentColor = c.textPrimary.copy(alpha = WakeMeTokens.opacityDisabled),
        ),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
        interactionSource = interaction,
    ) {
        ButtonLabel(text, MaterialTheme.typography.titleMedium, icon)
    }
}

/** Destructive and SOS actions only. Never animated: it must look and act the same every time. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: WMIcon? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.buttonHeight),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = WM.colors.dangerFill, contentColor = WM.colors.textOnDanger),
        contentPadding = PaddingValues(horizontal = WakeMeTokens.buttonPaddingX, vertical = WakeMeTokens.spaceSm),
    ) { ButtonLabel(text, MaterialTheme.typography.titleMedium, icon) }
}

/**
 * A tertiary action in the accent text colour. No side padding, so its label lines up with the
 * text it sits under (Material's 12 dp inset made these look indented); still a 48 dp target.
 * Material's pill shape clipped the first letters once the label wrapped at large text ("Use the
 * simple check-in instead" in the run 76 previews); corners no larger than the vertical padding
 * stay clear of the label.
 */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: WMIcon? = null) {
    val color = WM.colors.accentText.copy(alpha = if (enabled) 1f else WakeMeTokens.opacityDisabled)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = WakeMeTokens.minTouchTarget, minHeight = WakeMeTokens.minTouchTarget),
        shape = RoundedCornerShape(WakeMeTokens.radiusSm),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = WakeMeTokens.spaceXs),
    ) {
        if (icon != null) {
            WMIconView(icon, tint = color, size = WakeMeTokens.iconSizeSmall)
            Spacer(Modifier.width(WakeMeTokens.spaceXs))
        }
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A compact pill (40 dp visual in a 48 dp target) for an inline action: an icon and a short label,
 * sized to its words (the reference apps' "Invite" and "Play" pills). [primary] makes it lime.
 */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: WMIcon? = null, primary: Boolean = false, enabled: Boolean = true) {
    val c = WM.colors
    val fill = if (primary) c.accent else quietFill()
    val ink = (if (primary) c.textOnAccent else c.textPrimary).copy(alpha = if (enabled) 1f else WakeMeTokens.opacityDisabled)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    Row(
        modifier
            .heightIn(min = WakeMeTokens.minTouchTarget)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = WakeMeTokens.compactControlHeight)
                .graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
                .background(if (pressed && primary) c.accentPressed else if (pressed) pressedOf(fill) else fill, CircleShape)
                .padding(horizontal = WakeMeTokens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) WMIconView(icon, tint = ink, size = 18.dp)
            Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
        }
    }
}

/**
 * An icon-only control for actions everyone recognises (back, close, more, play a preview): a 44 dp
 * circle in a 48 dp target. [contentDescription] is required: it is what TalkBack says.
 */
@Composable
fun RoundIconButton(
    icon: WMIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color? = null,
    tint: Color? = null,
) {
    val c = WM.colors
    val fill = container ?: quietFill()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    Box(
        modifier
            .size(WakeMeTokens.minTouchTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(WakeMeTokens.iconButtonVisual)
                .graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
                .background(if (pressed) pressedOf(fill) else fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            WMIconView(icon, tint = (tint ?: c.textPrimary).copy(alpha = if (enabled) 1f else WakeMeTokens.opacityDisabled), size = WakeMeTokens.iconSizeSmall + 2.dp)
        }
    }
}

/**
 * A quick action (the reference apps' row of round buttons): a 56 dp circle with an icon and one
 * short word under it. [primary] is the lime action; [family] colours a circle by kind; otherwise
 * it is quiet grey. The word is always visible and is what TalkBack reads with [onClickLabel].
 */
@Composable
fun QuickAction(
    icon: WMIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    family: WakeMeFamily? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val c = WM.colors
    val fill = when {
        primary -> c.accent
        // A family colour at half strength: the row reads as soft petals under the face and name.
        family != null -> family.fill.copy(alpha = 0.5f)
        else -> quietFill()
    }
    val ink = when {
        primary -> c.textOnAccent
        family != null -> family.ink
        else -> c.textPrimary
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = rememberPressScale(interaction, enabled)
    Column(
        modifier
            .alpha(if (enabled) 1f else WakeMeTokens.opacityDisabled)
            .clip(RoundedCornerShape(WakeMeTokens.radiusMd))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(vertical = WakeMeTokens.spaceXxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs),
    ) {
        Box(
            Modifier
                .size(WakeMeTokens.actionCircle)
                .graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
                .background(if (pressed) pressedOf(fill) else fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) { WMIconView(icon, tint = ink) }
        Text(label, style = WakeMeType.actionLabel, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A flat card: neutral grey on the white page by day, a lighter tonal surface by night; no border,
 * no shadow (every reviewed app separates cards by fill alone). With [onClick] the whole card is one
 * target with press feedback; [onClickLabel] tells TalkBack what a tap does ("Open Maya").
 */
@Composable
fun WMCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    container: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(WakeMeTokens.radiusLg)
    val colors = CardDefaults.cardColors(containerColor = container ?: WM.colors.surfaceRaised)
    val inner: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalOnCard provides true) {
            Column(Modifier.padding(WakeMeTokens.layoutCardPadding), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs), content = content)
        }
    }
    if (onClick == null) {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, content = inner)
    } else {
        val interaction = remember { MutableInteractionSource() }
        val scale = rememberPressScale(interaction)
        Card(
            modifier = modifier.fillMaxWidth().graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
                .clip(shape)
                .clickable(interactionSource = interaction, indication = ripple(), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
            shape = shape, colors = colors, content = inner,
        )
    }
}

/**
 * The forest hero card (Bloom's one dark-green surface by day): the next alarm, a routine's time.
 * Text on it uses onHero (lime) and onHeroSecondary; a lime button on it still reads as the action.
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(WakeMeTokens.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, onClick != null)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
            .clip(shape)
            .background(WM.colors.heroFill)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = ripple(), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(WakeMeTokens.layoutHeroPadding),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs),
        content = content,
    )
}

/**
 * A bento tile: a petal-coloured block with its icon top-left and its title at the bottom-left
 * (the reference apps' account and category tiles). Tiles of one kind take different families,
 * like petals of one flower. Place two in a [BentoRow]; the whole tile is one target.
 */
@Composable
fun BentoTile(
    title: String,
    icon: WMIcon,
    family: WakeMeFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClickLabel: String? = null,
) {
    val shape = RoundedCornerShape(WakeMeTokens.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Column(
        modifier
            .heightIn(min = WakeMeTokens.tileMinHeight)
            .graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
            .clip(shape)
            .background(family.fill)
            .clickable(interactionSource = interaction, indication = ripple(color = family.ink), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(WakeMeTokens.layoutCardPadding),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs),
    ) {
        Box(Modifier.size(WakeMeTokens.iconChip).background(family.ink.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            WMIconView(icon, tint = family.ink, size = WakeMeTokens.iconSize)
        }
        Spacer(Modifier.weight(1f).heightIn(min = WakeMeTokens.spaceSm))
        Text(title, style = WakeMeType.tileTitle, color = family.ink)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = family.ink) }
    }
}

/** Two (or three) bento tiles side by side, each the height of the tallest. */
@Composable
fun BentoRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.layoutCardGap), content = content)
}

/**
 * A row that opens something: an icon chip, a title and an optional line under it, and a chevron.
 * The whole row is one target (Settings-style lists on the You tab).
 */
@Composable
fun ListRow(
    title: String,
    icon: WMIcon,
    family: WakeMeFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(WakeMeTokens.radiusMd))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else WakeMeTokens.opacityDisabled)
            .padding(vertical = WakeMeTokens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm),
    ) {
        IconChip(icon, family)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
            supporting?.let { Supporting(it) }
        }
        WMIconView(WMIcon.ChevronRight, tint = WM.colors.textSecondary, size = WakeMeTokens.iconSizeSmall)
    }
}

enum class Tone { Neutral, Info, Success, Warning, Danger }

/**
 * Status is always words plus a dot (or an icon), never colour alone. The tinted pill makes the
 * tone visible at a glance; a change crossfades (no movement). With [announce], TalkBack reads
 * changes politely, for statuses that change while the person waits (delivery, readiness).
 */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, announce: Boolean = false, icon: WMIcon? = null) {
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
        if (icon != null) WMIconView(icon, tint = dotColor, size = 16.dp) else Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.size(6.dp))
        AnimatedContent(targetState = text, transitionSpec = { WMMotion.fadeThrough() }, label = "pillText") { t ->
            Text(t, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
        }
    }
}

/**
 * A header between groups of content, outside cards (22 sp Bold). It carries extra space above
 * so it belongs to what follows, not to what came before.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = WM.colors.textPrimary, modifier = modifier.padding(top = WakeMeTokens.layoutSectionTopExtra).semantics { heading() })
}

/** A small header over a list (14 sp SemiBold, quieter), with an optional action at its end ("See all"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = WakeMeTokens.layoutSectionTopExtra), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = WM.colors.textSecondary, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/**
 * The hero headline: sentence case and system typography (tokens: type.roles.display), in forest by
 * day and lime by night. TalkBack reads the sentence as written, not letter by letter.
 */
@Composable
fun DisplayText(text: String, modifier: Modifier = Modifier, color: Color = WM.colors.brandInk, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = DisplayStyle,
        color = color,
        textAlign = textAlign,
        modifier = modifier.semantics { heading(); contentDescription = text },
    )
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

/**
 * A caption under the control it qualifies, the way the reference apps write footnotes: a short
 * line of secondary text, no icon (an icon beside wrapped text never lines up with it). Most
 * screens need none: people who use apps already know what a switch or a button does.
 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = WM.colors.textSecondary, modifier = modifier.fillMaxWidth())
}

/**
 * A wait, shown as motion rather than words (a spinner reads as "working" at a glance; "Loading…"
 * text makes the wait feel longer). Read as "Loading".
 */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = WakeMeTokens.spaceLg).semantics { contentDescription = "Loading" }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = WM.colors.accentText, strokeWidth = 3.dp)
    }
}

/**
 * How far through a short task you are, as segments that fill one by one (the goal-gradient: the
 * nearer the end looks, the more people finish). Read as "Step n of total".
 */
@Composable
fun StepProgress(total: Int, done: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Step ${minOf(done + 1, total)} of $total" }, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
        repeat(total) { i ->
            val fill by animateColorAsState(if (i < done) WM.colors.accent else WM.colors.surfaceNeutral, WMMotion.standard(), label = "step")
            Box(Modifier.weight(1f).height(6.dp).background(fill, CircleShape))
        }
    }
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = WM.colors.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier)
}

@Composable
fun Gap(height: Dp = WakeMeTokens.spaceMd) = Spacer(Modifier.height(height))

/**
 * A toggle row whose whole surface is the touch target and reads as a switch to TalkBack. An
 * [icon] in its [family] colour leads the row where a list of settings needs scanning.
 */
@Composable
fun SettingRow(title: String, subtitle: String?, checked: Boolean, icon: WMIcon? = null, family: WakeMeFamily? = null, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = WakeMeTokens.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { haptics.toggle(it); onChange(it) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm),
    ) {
        if (icon != null) IconChip(icon, family ?: WM.palette.lime)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = WM.colors.textPrimary, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WM.colors.textSecondary) }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

/** A number with − and + buttons, each a full 48 dp target with its own spoken label. */
@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = WM.colors.textPrimary)
        RoundIconButton(WMIcon.Minus, "Fewer", onClick = { haptics.selection(); onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min)
        RoundIconButton(WMIcon.Plus, "More", onClick = { haptics.selection(); onChange((value + 1).coerceAtMost(max)) }, enabled = value < max)
    }
}

/**
 * A single choice among a few (sounds, ring length, presets). Selected chips are lime with a check
 * mark, so the state never depends on colour alone; unselected chips are quiet grey (white on a
 * card) and may lead with an [icon]. A light selection haptic confirms the change.
 */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: WMIcon? = null) {
    val c = WM.colors
    val haptics = rememberHaptics()
    FilterChip(
        selected = selected,
        onClick = { haptics.selection(); onClick() },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.heightIn(min = WakeMeTokens.chipHeight),
        enabled = enabled,
        leadingIcon = when {
            selected -> { { WMIconView(WMIcon.Check, tint = c.textOnAccent, size = FilterChipDefaults.IconSize) } }
            icon != null -> { { WMIconView(icon, tint = c.textPrimary, size = FilterChipDefaults.IconSize) } }
            else -> null
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = quietFill(),
            labelColor = c.textPrimary,
            selectedContainerColor = c.accent,
            selectedLabelColor = c.textOnAccent,
            selectedLeadingIconColor = c.textOnAccent,
        ),
        border = null,
    )
}

/**
 * A friend's face: one of ten cartoon animals, chosen from a stable key so the same friend always
 * has the same animal (and the same one on iOS: same hash, same order). Decorative: the name beside
 * it is what TalkBack reads. [name] stays in the signature for callers and previews.
 */
@Composable
fun FriendAvatar(name: String, key: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val family = WM.palette.avatars[Math.floorMod(key.hashCode(), WM.palette.avatars.size)]
    val initials = name.trim().split(' ').filter { it.isNotBlank() }.take(2)
        .joinToString("") { String(Character.toChars(it.codePointAt(0))).uppercase() }.ifEmpty { "?" }
    Box(modifier.size(size).clip(CircleShape).background(family.fill).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.34f).sp), color = family.ink)
    }
}

/** The animal for a stable key (a friend's or circle's id); the same on iOS. */
fun faceFor(key: String): WMFace = WMFace.avatars[Math.floorMod(key.hashCode(), WMFace.avatars.size)]

/**
 * Sent → reached their phone → seen (quack) / rang (wake alarm), as three icon stops on a track
 * (the reference apps' order trackers). Steps fill only for evidence the server reported
 * ([DeliveryTrail], shared fixture), and the fill animates only when that status changes, never on
 * a timer. Visual only: the literal status pill beside it is what TalkBack reads and announces, so
 * nothing is said twice.
 */
@Composable
fun DeliveryTrailView(displayStatus: String, alarm: Boolean, modifier: Modifier = Modifier) {
    val c = WM.colors
    val trail = DeliveryTrail.of(displayStatus, alarm)
    val fill by animateFloatAsState(trail.reached.toFloat(), WMMotion.smooth(), label = "trail")
    val labels = listOf("Sent", "Reached their phone", if (alarm) "Rang on their phone" else "They saw it")
    val stepIcons = listOf(WMIcon.Send, WMIcon.Phone, if (alarm) WMIcon.Wake else WMIcon.Eye)
    val node = 32.dp
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs),
    ) {
        Box(Modifier.fillMaxWidth().height(node)) {
            Canvas(Modifier.matchParentSize()) {
                val r = node.toPx() / 2
                val y = size.height / 2
                val xs = listOf(r, size.width / 2, size.width - r)
                // Track, then the reached part. `fill` 1 ends at the first stop, 3 at the last.
                drawLine(c.border, Offset(xs[0], y), Offset(xs[2], y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                if (fill > 1f) {
                    val f = (fill - 1f).coerceIn(0f, 2f)
                    val end = if (f <= 1f) xs[0] + (xs[1] - xs[0]) * f else xs[1] + (xs[2] - xs[1]) * (f - 1f)
                    drawLine(c.success, Offset(xs[0], y), Offset(end, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                stepIcons.forEachIndexed { i, icon ->
                    val reached = i < trail.reached
                    val failedHere = i == trail.reached && trail.state == TrailState.PROBLEM
                    val (bg, ink) = when {
                        reached -> c.accent to c.textOnAccent
                        failedHere -> c.warningContainer to c.warning
                        else -> c.surfaceNeutral to c.textSecondary
                    }
                    Box(Modifier.size(node).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                        WMIconView(if (failedHere) WMIcon.Close else icon, tint = ink, size = 16.dp)
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
 * A visible way back on detail and editor screens (system Back and predictive back still work):
 * a round icon button, spoken as "Back", with optional [actions] (more, share) at the end.
 */
@Composable
fun DetailTopBar(onBack: () -> Unit, modifier: Modifier = Modifier, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
        RoundIconButton(WMIcon.Back, "Back", onClick = onBack)
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/**
 * A pushed screen: a pinned back bar (system Back and predictive back still work) above scrolling
 * content with the standard margins and rhythm. Without [onBack] (a top-level use) it is just the
 * scrolling column.
 */
@Composable
fun DetailScreen(onBack: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        if (onBack != null) DetailTopBar(onBack = onBack, modifier = Modifier.padding(horizontal = WakeMeTokens.spaceXs), title = title, actions = actions)
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
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    WMCard(modifier) {
        // A friendly face beside the words, title over body, as on iOS; a body only where needed.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            Image(painterResource(faceFor(title).res), contentDescription = null, modifier = Modifier.size(52.dp).clearAndSetSemantics { })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary, modifier = Modifier.semantics { heading() })
                if (body != null) Supporting(body)
            }
        }
        action?.invoke()
    }
}


/** Keep uncommon settings available without filling the first screen with choices. */
@Composable
fun DisclosureGroup(label: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = WakeMeTokens.buttonHeight)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = WM.colors.textSecondary)
            }
            Text(if (expanded) "Hide" else "Edit", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) Column(Modifier.padding(WakeMeTokens.spaceSm), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd), content = content)
    }
}
