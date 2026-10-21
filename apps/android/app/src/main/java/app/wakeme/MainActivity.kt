package app.wakeme

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.push.NotificationRoutes
import app.wakeme.ui.AppViewModel
import app.wakeme.ui.WakeMeRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { WakeMeTheme { WakeMeRoot(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Invite links hold only an opaque token: https://wakeme.app/i/<token>. Nothing is accepted automatically. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "https" && uri.host == "wakeme.app" && uri.pathSegments.size == 2 && uri.pathSegments.firstOrNull() == "i") {
            uri.pathSegments.getOrNull(1)?.takeIf { Regex("^[A-Za-z0-9_-]{20,64}$").matches(it) }?.let { app.onInviteLink(it) }
        }
        // This activity is exported (launcher, invite links), so any app can start it: only the
        // routes this app's own notifications create are followed, with plain ids.
        intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { NotificationRoutes.isAllowed(it) }?.let { app.openRoute(it) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
