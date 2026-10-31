package app.wakeme.ui.you

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.wakeme.designsystem.*

/** The real You overview, separated from account loading so every form factor can be rendered. */
@Composable
fun ProfileOverview(name: String, personKey: String, timeZone: String, plus: Boolean,
    onSafety: () -> Unit, onFocus: () -> Unit, onVoiceSounds: () -> Unit, onPaywall: () -> Unit) {
    val palette = WM.palette
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FriendAvatar(name, personKey, size = 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineMedium, color = WM.colors.onHero)
                    Text(timeZone, style = MaterialTheme.typography.bodyMedium, color = WM.colors.onHeroSecondary)
                }
            }
        }
        BentoRow {
            BentoTile("Safety check-ins and location", WMIcon.Shield, palette.orchid, onClick = onSafety, modifier = Modifier.weight(1f).fillMaxHeight())
            BentoTile("Focus", WMIcon.Focus, palette.aqua, onClick = onFocus, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        BentoRow {
            BentoTile("Voice sounds", WMIcon.Mic, palette.apricot, onClick = onVoiceSounds, modifier = Modifier.weight(1f).fillMaxHeight())
            BentoTile("Wake Me Plus", WMIcon.Sparkle, palette.gold, onClick = onPaywall, modifier = Modifier.weight(1f).fillMaxHeight(), supporting = if (plus) "Manage subscription" else "See what's included")
        }
    }
}
