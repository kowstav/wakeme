package app.wakeme.ui.friends

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.wakeme.MainActivity
import app.wakeme.R
import app.wakeme.core.Capability
import app.wakeme.data.net.FriendDto

/**
 * Launcher shortcuts ("Quack Alex", long-press the app icon) for friends who currently allow
 * quacks (doc 06: less effort for the sender). A shortcut only opens that friend's page; the
 * quack is still sent from there and authorized by the server.
 */
object QuackShortcuts {
    private const val MAX = 4

    fun publish(context: Context, friends: List<FriendDto>) {
        val shortcuts = friends
            .filter { f -> f.actions.any { it.capability == Capability.NUDGE && it.granted } }
            .take(MAX)
            .map { f ->
                ShortcutInfoCompat.Builder(context, "quack:${f.friendshipId}")
                    .setShortLabel("Quack ${f.user.displayName}")
                    .setLongLabel("Send ${f.user.displayName} a quack")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_ROUTE, "friend/${f.friendshipId}"))
                    .build()
            }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
