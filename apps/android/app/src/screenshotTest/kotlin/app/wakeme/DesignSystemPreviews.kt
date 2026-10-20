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
import app.wakeme.designsystem.ChoiceChip
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
 * The Daybreak design system (docs/design/system.md) on one sheet: the duck, avatars, buttons,
 * chips, status pills, the delivery trail in every evidence state, day toggles and the time
 * display. Rendered in light, dark, increased contrast and at 2× font size by the CI previews job.
 */
@Composable
private fun Sheet() {
    val c = WM.colors
    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
            DuckMark(size = 64.dp)
            DuckMark(size = 64.dp, beakOpen = 0.8f)
            FriendAvatar("Maya", "u-maya")
            FriendAvatar("Sam", "u-sam")
            FriendAvatar("Alex", "u-alex")
        }
        WMCard {
            Text("Quack", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                ChoiceChip("Classic quack", selected = true, onClick = {})
                ChoiceChip("Double quack", selected = false, onClick = {})
            }
            SecondaryButton("Preview on my phone", onClick = {})
            PrimaryButton("Send quack", onClick = {})
            PrimaryButton("Send wake alarm", onClick = {}, enabled = false)
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
        QuietButton("Remove friend", onClick = {})
        DangerButton("Block and report", onClick = {})
    }
}

@PreviewTest
@Preview(name = "Light", widthDp = 411, heightDp = 1800)
@Composable
fun DaybreakLight() = WakeMeTheme(dark = false) { Sheet() }

@PreviewTest
@Preview(name = "Dark", widthDp = 411, heightDp = 1800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DaybreakDark() = WakeMeTheme(dark = true) { Sheet() }

@PreviewTest
@Preview(name = "Increased contrast", widthDp = 411, heightDp = 1800)
@Composable
fun DaybreakHighContrast() = WakeMeTheme(dark = false, highContrast = true) { Sheet() }

@PreviewTest
@Preview(name = "Large text", widthDp = 411, heightDp = 2600, fontScale = 2f)
@Composable
fun DaybreakLargeText() = WakeMeTheme(dark = false) { Sheet() }
