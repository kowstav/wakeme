package app.wakeme.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps this screen out of the Recents thumbnail (doc 02 §10: mask sensitive screens in the app
 * switcher) while it is shown. Unlike FLAG_SECURE it doesn't stop the person taking their own
 * screenshot or screen reader access (ADR 0012). Android 13+; earlier versions show the thumbnail.
 */
@Composable
fun HideFromRecents() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
        onDispose { if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(true) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
