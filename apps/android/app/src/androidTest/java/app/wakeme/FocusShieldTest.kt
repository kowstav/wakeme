package app.wakeme

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileInputStream
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

    /** The shield has laid out its content: its first frame can come over a second after it resumes. */
    private fun shieldDrawn(): Boolean {
        var drawn = false
        instrumentation.runOnMainSync {
            drawn = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FocusShieldActivity>().any { shield ->
                val content = shield.findViewById<ViewGroup>(android.R.id.content)
                content.childCount > 0 && content.getChildAt(0).let { it.isLaidOut && it.height > 0 }
            }
        }
        return drawn
    }

    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    /** Bound by the system, not just listed in settings (UiAutomation can unbind services). */
    private fun serviceBound(): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

    private fun openSettings() = context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test
    fun aChosenAppShowsTheShieldOnlyDuringFocus() {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assumeTrue("focus service not enabled on this device: $enabled", enabled.contains("FocusShieldService"))
        // `am instrument` force-stops the app, which unbinds its accessibility service while the
        // setting still lists it (run 30). Turning it off and on, as a person would in Settings,
        // makes the system bind it again. UiAutomation keeps other services bound.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use { pfd -> FileInputStream(pfd.fileDescriptor).use { String(it.readBytes()) } }
        // One "<name> services:{...}" line of the system's accessibility user state.
        fun systemState(name: String) = shell("dumpsys accessibility").lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$name services:{") }
        // The killed connection stays in the system's crashed or binding set (run 62's dumps show
        // both), which it skips while the service is enabled and clears only when it reads the
        // setting without the service. Its observer
        // reads the current value, so writing it back before the observer ran looked unchanged and
        // nothing was bound (Android 16, runs 55, 58 and 60). Wait until the system shows it off.
        var cycles = 0
        while (!serviceBound() && cycles < 3) {
            cycles++
            shell("settings delete secure enabled_accessibility_services")
            waitFor(5_000) { systemState("Enabled")?.contains("FocusShieldService") == false }
            shell("settings put secure enabled_accessibility_services $enabled")
            shell("settings put secure accessibility_enabled 1")
            waitFor(10_000) { serviceBound() }
        }
        val bound = serviceBound()
        val state = if (bound) "" else listOf("Enabled", "Binding", "Crashed").joinToString("; ") { systemState(it) ?: "$it services: not in dump" }
        DeviceEvidence.note("focus-binding", if (bound) "bound after $cycles off/on cycles" else "not bound after $cycles off/on cycles; $state")
        assertTrue("the system binds the focus service (after $cycles off/on cycles) $state", bound)
        val settingsPkg = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName ?: "com.android.settings"
        val store = FocusStore(context)
        try {
            store.chosenApps = setOf(settingsPkg)
            store.start(startFocus("instrumented", System.currentTimeMillis(), 25)!!)
            openSettings()
            assertTrue("the shield covers the chosen app during focus", waitFor(15_000) { shieldShowing() })
            // Screenshots taken 0.8 s after it resumed showed only the window background (runs 56,
            // 64 and 76; logcat put its first frame at +1.6 s on the software-rendered emulator).
            assertTrue("the shield draws its content", waitFor(10_000) { shieldDrawn() })
            Thread.sleep(800)
            DeviceEvidence.screenshot("13-focus-shield")

            store.end()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<FocusShieldActivity>().forEach { it.finish() }
            }
            // finish() is asynchronous: on Android 17 the closing shield was still RESUMED when the
            // next check ran (runs 61, 64 and 65; no shield was started after End focus).
            assertTrue("the finished shield closes", waitFor(4_000) { !shieldShowing() })
            openSettings()
            assertFalse("no shield after End focus", waitFor(4_000) { shieldShowing() })
        } finally {
            store.clear()
        }
    }
}
