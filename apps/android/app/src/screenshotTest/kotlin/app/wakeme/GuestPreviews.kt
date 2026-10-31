package app.wakeme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.wakeme.core.InstalledRoutine
import app.wakeme.core.Recurrence
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.ui.routines.GuestAlarmContent
import app.wakeme.ui.routines.GuestState
import com.android.tools.screenshot.PreviewTest

private val localMorning = InstalledRoutine(
    planId = "local_preview", version = 1, label = "Morning class", localTime = "07:00",
    timeZone = "Europe/London", timeZonePolicy = "fixed", recurrence = Recurrence.weekly(listOf(1,2,3,4,5)),
    soundId = "bundled:rise.gentle", ringSeconds = 90, challengeRevisionId = null,
    followUpDelaySeconds = null, paused = false,
)

@PreviewTest
@Preview(name = "Phone", widthDp = 393, heightDp = 852)
@Preview(name = "Dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text", widthDp = 393, heightDp = 852, fontScale = 1.6f)
@Composable
fun LocalAlarmHome() = WakeMeTheme {
    GuestAlarmContent(GuestState(listOf(localMorning to true)), onNew = {}, onReady = {}, onSignIn = {}, onDelete = {})
}

@PreviewTest
@Preview(name = "Empty phone", widthDp = 393, heightDp = 852)
@Composable
fun LocalAlarmEmpty() = WakeMeTheme {
    GuestAlarmContent(GuestState(), onNew = {}, onReady = {}, onSignIn = {}, onDelete = {})
}
