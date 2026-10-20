package app.wakeme

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.wakeme.core.startFocus
import app.wakeme.focus.FocusShieldActivity
import app.wakeme.focus.FocusStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0014 on an emulator: with the accessibility service turned on (CI does it with
 * `settings put secure enabled_accessibility_services`), opening an app the person chose shows
 * the focus shield during a session, and not after "End focus". Not device evidence.
 */
@RunWith(AndroidJUnit4::class)
class FocusShieldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shieldShowing(): Boolean {
        var showing = false
        instrumentation.runOnMainSync {
            showing = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is FocusShieldActivity }
        }
        return showing
    }

    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    private fun openSettings() = context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test
    fun aChosenAppShowsTheShieldOnlyDuringFocus() {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assumeTrue("focus service not enabled on this device: $enabled", enabled.contains("FocusShieldService"))
        val settingsPkg = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName ?: "com.android.settings"
        val store = FocusStore(context)
        try {
            store.chosenApps = setOf(settingsPkg)
            store.start(startFocus("instrumented", System.currentTimeMillis(), 25)!!)
            openSettings()
            assertTrue("the shield covers the chosen app during focus", waitFor(15_000) { shieldShowing() })
            Thread.sleep(800)
            DeviceEvidence.screenshot("13-focus-shield")

            store.end()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FocusShieldActivity>().forEach { it.finish() }
            }
            openSettings()
            assertFalse("no shield after End focus", waitFor(4_000) { shieldShowing() })
        } finally {
            store.clear()
        }
    }
}
