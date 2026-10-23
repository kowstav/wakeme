package app.wakeme.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMMotion
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.designsystem.rememberHaptics
import app.wakeme.designsystem.rememberReducedMotion
import app.wakeme.ui.onboarding.playSoundPreview
import kotlinx.coroutines.delay

/** One animal in a sound row: its face, ringed in lime when chosen, and its word under it. */
@Composable
fun SoundChoice(sound: AnimalSound, selected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(sound.face.res),
            contentDescription = null,
            modifier = Modifier
                .size(58.dp)
                .border(3.dp, if (selected) WM.colors.accent else Color.Transparent, CircleShape)
                .padding(3.dp),
        )
        Text(
            sound.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) WM.colors.textPrimary else WM.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = WakeMeTokens.spaceXxs),
        )
    }
}

/**
 * The animals a friend allowed, side by side: tapping one picks it and plays it here (so you hear
 * what they will), and the send button takes its word.
 */
@Composable
fun AnimalSoundRow(allowed: List<String>, selection: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs)) {
        AnimalSounds.all.filter { it.id in allowed }.forEach { s ->
            SoundChoice(
                s,
                selected = s.id == selection,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.RadioButton) { haptics.selection(); onSelect(s.id); playSoundPreview(context, s.id) }
                    .semantics { selected = s.id == selection },
            )
        }
    }
}

/** The animals a friend may send you: each face toggles, all on by default; at least one stays. */
@Composable
fun AnimalSoundGrid(sounds: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXxs), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        AnimalSounds.all.forEach { s ->
            val on = s.id in sounds
            SoundChoice(
                s,
                selected = on,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Checkbox) {
                        if (on) { if (sounds.size > 1) onChange(sounds - s.id) } else { onChange(sounds + s.id); playSoundPreview(context, s.id) }
                    }
                    .semantics { selected = on }
                    .alpha(if (on) 1f else 0.45f),
            )
        }
    }
}

/**
 * Five friends' faces in a slight arc, the middle one largest: the welcome's picture of the idea.
 * Tapping a face plays its call here and gives it a little hop (with Remove animations, only the
 * sound).
 */
@Composable
fun FriendHuddle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    val huddle = listOf(Triple("bundled:cat.meow", 64.dp, 10.dp), Triple("bundled:frog.ribbit", 76.dp, 2.dp), Triple("bundled:dog.woof", 92.dp, 0.dp), Triple("bundled:monkey.hoot", 76.dp, 2.dp), Triple("bundled:cow.moo", 64.dp, 10.dp))
    var hopping by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hopping) { if (hopping != null) { delay(220); hopping = null } }
    Row(modifier.fillMaxWidth().padding(top = WakeMeTokens.spaceMd), horizontalArrangement = Arrangement.spacedBy((-10).dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        huddle.forEach { (id, size, lift) ->
            val sound = AnimalSounds.sound(id) ?: return@forEach
            val hop by animateDpAsState(if (hopping == id && !reduced) 10.dp else 0.dp, WMMotion.playful(), label = "hop")
            Face(sound, size, Modifier.offset(y = -(lift + hop)).clickable(onClickLabel = sound.name, role = Role.Button) { playSoundPreview(context, id); hopping = id })
        }
    }
}

@Composable
private fun Face(sound: AnimalSound, size: Dp, modifier: Modifier) {
    Image(painterResource(sound.face.res), contentDescription = null, modifier = modifier.size(size).semantics { contentDescription = sound.name; role = Role.Button })
}

/** A face that shows a sound without being a control (the "You're up" finish, empty states). */
@Composable
fun FaceImage(sound: AnimalSound, size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(sound.face.res), contentDescription = null, modifier = modifier.size(size).clearAndSetSemantics { })
}
