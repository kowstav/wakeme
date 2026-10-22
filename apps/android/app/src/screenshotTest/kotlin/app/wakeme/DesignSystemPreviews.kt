package app.wakeme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.BentoRow
import app.wakeme.designsystem.BentoTile
import app.wakeme.designsystem.ChoiceChip
import app.wakeme.designsystem.DisplayText
import app.wakeme.designsystem.HeroCard
import app.wakeme.designsystem.IconChip
import app.wakeme.designsystem.PillButton
import app.wakeme.designsystem.QuickAction
import app.wakeme.designsystem.RoundIconButton
import app.wakeme.designsystem.WMIcon
import app.wakeme.designsystem.WMIconView
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import app.wakeme.designsystem.DangerButton
import app.wakeme.designsystem.DeliveryTrailView
import app.wakeme.designsystem.DuckMark
import app.wakeme.designsystem.EmptyState
import app.wakeme.designsystem.FriendAvatar
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.designsystem.WakeMeTokens
import app.wakeme.ui.grants.DayChips
import app.wakeme.ui.grants.TimeDisplay
import com.android.tools.screenshot.PreviewTest

/**
 * The Bloom design system (docs/design/system.md) on one sheet: the display headline, the duck,
 * avatars in their petal families, the icon set, quick actions, bento tiles, the hero, buttons
 * with icons, chips, status pills, the delivery trail in every evidence state, day toggles and the
 * time hero. Rendered in light, dark, increased contrast and at 2× font size by the CI previews job.
 */
@Composable
private fun Sheet() {
    val c = WM.colors
    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
    ) {
        DisplayText("Alert your friends. Wake each other up.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            DuckMark(size = 64.dp)
            DuckMark(size = 64.dp, beakOpen = 0.8f)
            FriendAvatar("Maya", "u-maya")
            FriendAvatar("Sam", "u-sam")
            FriendAvatar("Alex", "u-alex")
            FriendAvatar("Priya", "u-priya")
        }
        // Every icon in the set, so a broken path shows up in review.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            WMIcon.entries.forEach { WMIconView(it) }
        }
        val p = WM.palette
        Row(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            listOf(p.lime, p.orchid, p.sky, p.apricot, p.gold, p.aqua, p.periwinkle, p.forest).forEachIndexed { i, f -> IconChip(WMIcon.entries[i], f) }
        }
        Row(Modifier.fillMaxWidth()) {
            QuickAction(WMIcon.Message, "Messages", onClick = {}, family = p.sky, modifier = Modifier.weight(1f))
            QuickAction(WMIcon.CheckCircle, "Check in", onClick = {}, family = p.aqua, modifier = Modifier.weight(1f))
            QuickAction(WMIcon.Pin, "Location", onClick = {}, family = p.periwinkle, enabled = false, modifier = Modifier.weight(1f))
            QuickAction(WMIcon.CalendarPlus, "Suggest", onClick = {}, family = p.apricot, modifier = Modifier.weight(1f))
            QuickAction(WMIcon.Shield, "Safety", onClick = {}, family = p.orchid, modifier = Modifier.weight(1f))
        }
        HeroCard {
            Text("Next alarm on this phone", style = MaterialTheme.typography.labelLarge, color = c.onHeroSecondary)
            Text("07:00", style = MaterialTheme.typography.displayMedium, color = c.onHero)
            Text("Tomorrow · Work days", style = MaterialTheme.typography.titleMedium, color = c.onHeroSecondary)
        }
        BentoRow {
            BentoTile("Invite a friend", WMIcon.PersonPlus, p.lime, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight(), supporting = "Send a private link")
            BentoTile("Join with a code", WMIcon.Ticket, p.sky, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight(), supporting = "From a friend's invite")
        }
        BentoRow {
            BentoTile("Safety check-ins and location", WMIcon.Shield, p.orchid, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight())
            BentoTile("Focus", WMIcon.Focus, p.aqua, onClick = {}, modifier = Modifier.weight(1f).fillMaxHeight(), supporting = "Time with chosen apps paused")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(WMIcon.Back, "Back", onClick = {})
            RoundIconButton(WMIcon.More, "More", onClick = {})
            PillButton("Play preview", onClick = {}, icon = WMIcon.Speaker)
            PillButton("Invite", onClick = {}, icon = WMIcon.PersonPlus, primary = true)
        }
        WMCard {
            Text("Quack", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                ChoiceChip("Classic quack", selected = true, onClick = {})
                ChoiceChip("Double quack", selected = false, onClick = {})
            }
            SecondaryButton("Change permissions", onClick = {}, icon = WMIcon.Sliders)
            PrimaryButton("Send quack", onClick = {}, icon = WMIcon.Send)
            PrimaryButton("Send wake alarm", onClick = {}, enabled = false, icon = WMIcon.Wake)
        }
        WMCard {
            Text("Last request", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            StatusPill("Sent · no confirmation from their phone yet", Tone.Info)
            DeliveryTrailView("no_confirmation_yet", alarm = false)
            StatusPill("Their phone received it", Tone.Info)
            DeliveryTrailView("phone_received", alarm = false)
            StatusPill("They saw it 👍", Tone.Success)
            DeliveryTrailView("acknowledged", alarm = false)
            StatusPill("Couldn't reach their phone", Tone.Warning)
            DeliveryTrailView("not_delivered", alarm = false)
            StatusPill("Challenge completed (not proof they're awake)", Tone.Success)
            DeliveryTrailView("challenge_completed", alarm = true)
        }
        WMCard {
            TimeDisplay("Rings at", "07:00") {}
            DayChips(listOf(1, 2, 3, 4, 5)) {}
            StatusPill("Ready on this phone", Tone.Success)
            StatusPill("Saved · not yet confirmed on this phone", Tone.Warning)
            StatusPill("Paused", Tone.Neutral)
        }
        EmptyState("Invite someone you trust", "Send a private link. They choose what you can do on their phone, and you choose what they can do on yours.")
        QuietButton("New circle", onClick = {}, icon = WMIcon.Plus)
        DangerButton("End this routine", onClick = {}, icon = WMIcon.Trash)
    }
}

@PreviewTest
@Preview(name = "Light", widthDp = 411, heightDp = 2600)
@Composable
fun BloomLight() = WakeMeTheme(dark = false) { Sheet() }

@PreviewTest
@Preview(name = "Dark", widthDp = 411, heightDp = 2600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BloomDark() = WakeMeTheme(dark = true) { Sheet() }

@PreviewTest
@Preview(name = "Increased contrast", widthDp = 411, heightDp = 2600)
@Composable
fun BloomHighContrast() = WakeMeTheme(dark = false, highContrast = true) { Sheet() }

@PreviewTest
@Preview(name = "Large text", widthDp = 411, heightDp = 3800, fontScale = 2f)
@Composable
fun BloomLargeText() = WakeMeTheme(dark = false) { Sheet() }
