package app.wakeme.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

private const val PREFS = "wakeme_permission_asks"
private const val KEY_ASKED = "notifications_asked"

/** True once we have shown our explanation and the OS prompt; later reminders go to settings, not another prompt. */
fun notificationsAsked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

private fun markAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()

/**
 * Just-in-time notification permission (doc 03 §5 step 6): explain the benefit
 * in our own words first, then show the system prompt. Declining never blocks
 * the action that triggered it; it only means quacks can't be shown.
 *
 * Returns ask(reason, then): `then` always runs, after the user has decided.
 */
@Composable
fun rememberNotificationAsk(): (reason: String, then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var afterPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPrompt?.invoke()
        afterPrompt = null
    }
    pending?.let { (reason, then) ->
        AlertDialog(
            onDismissRequest = { pending = null; then() },
            title = { Text("Turn on notifications?") },
            text = { Text("$reason You choose which friends can make a sound, and when. You can change this anytime in settings.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    markAsked(context)
                    afterPrompt = then
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pending = null; markAsked(context); then() }) { Text("Not now") } },
        )
    }
    return { reason, then ->
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed(context) || notificationsAsked(context)) then() else pending = reason to then
    }
}
