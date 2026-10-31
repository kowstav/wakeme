package app.wakeme.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import app.wakeme.core.shouldShield
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Narrowly scoped (ADR 0014): it only notices which app comes to the front, and only acts while
 * a focus session the person started is active and not on a break. No window content is read,
 * no gestures or global actions are performed, and the rules are the person's own choices.
 */
@AndroidEntryPoint
class FocusShieldService : AccessibilityService() {
    @Inject lateinit var store: FocusStore

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in store.chosenApps) return
        if (!shouldShield(store.sessionNow(), System.currentTimeMillis())) return
        startActivity(
            Intent(this, FocusShieldActivity::class.java)
                .putExtra(FocusShieldActivity.EXTRA_APP, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    override fun onInterrupt() = Unit
}
