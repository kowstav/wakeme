package app.wakeme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.wakeme.data.net.InvitePreviewDto
import app.wakeme.data.net.PublicUser
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.focus.ShieldContent
import app.wakeme.ui.onboarding.InviteScreen
import app.wakeme.ui.onboarding.ProfileScreen
import com.android.tools.screenshot.PreviewTest

/** Setup steps and the focus shield, which have no ViewModel to stand in for. */
@PreviewTest
@Preview(name = "Phone", widthDp = 393, heightDp = 852)
@Preview(name = "Dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text", widthDp = 393, heightDp = 852, fontScale = 1.6f)
@Composable
fun FocusShield() = WakeMeTheme {
    ShieldContent(friendName = "Maya", minutesLeft = 25, breaksLeft = 2, onLeave = {}, onBreak = {}, onEnd = {})
}

@PreviewTest
@Preview(name = "Phone", widthDp = 393, heightDp = 852)
@Composable
fun Profile() = WakeMeTheme { ProfileScreen(onCreate = { _, _ -> }) }

@PreviewTest
@Preview(name = "Phone", widthDp = 393, heightDp = 852)
@Composable
fun Invite() = WakeMeTheme {
    InviteScreen(
        InvitePreviewDto(kind = "friend", inviter = PublicUser("u", "Maya"), expiresAt = "2026-11-02T08:00:00Z", ownInvite = false, alreadyConnected = false),
        onAccept = {}, onDecline = {},
    )
}
