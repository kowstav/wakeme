package app.wakeme

import android.Manifest
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.ui.safety.SystemLocationButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SessionLocationButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aSystemButtonTapReturnsTheRealPermissionResult() {
        val result = AtomicReference<Boolean?>(null)
        compose.setContent { WakeMeTheme { Box(Modifier.safeDrawingPadding().padding(24.dp)) { SystemLocationButton { result.set(it) } } } }
        onView(withId(R.id.session_location_button)).check(matches(isDisplayed()))
        DeviceEvidence.screenshot("56-session-location-button")
        Thread.sleep(2000) // remote secure rendering is asynchronous to Compose's idleness
        onView(withId(R.id.session_location_button)).perform(click())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val sessionConsent = Regex("^(allow|allow once|allow this time|allow for this session|allow precise location|allow & continue|share|share precise location|use precise location|only this time|while using (the app|this app)|continue|got it|turn on)$", RegexOption.IGNORE_CASE)
        fun allowOnce(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName?.endsWith(":id/permission_allow_one_time_button") == true) return node
            if (node.packageName?.toString()?.contains("permissioncontroller") == true && sessionConsent.matches(node.text?.toString().orEmpty())) {
                if (node.isClickable) return node
                node.parent?.takeIf { it.isClickable }?.let { return it }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> allowOnce(child)?.let { return it } }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (result.get() == null && SystemClock.elapsedRealtime() < deadline) {
            automation.rootInActiveWindow?.let { root -> allowOnce(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            Thread.sleep(200)
        }
        if (result.get() == null) {
            DeviceEvidence.screenshot("98-location-permission-dialog")
            fun describe(node: AccessibilityNodeInfo) {
                if (node.packageName?.toString()?.contains("permissioncontroller") == true && (!node.text.isNullOrBlank() || node.isClickable)) DeviceEvidence.note("location-dialog", "id=${node.viewIdResourceName}, text=${node.text}, clickable=${node.isClickable}")
                for (i in 0 until node.childCount) node.getChild(i)?.let(::describe)
            }
            automation.rootInActiveWindow?.let(::describe)
        }
        assertEquals("location callback after the user's explicit tap", true, result.get())
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.ACCESS_FINE_LOCATION))
        DeviceEvidence.note("session-location-button", "permission callback granted on API ${android.os.Build.VERSION.SDK_INT}; no SOS or location sample sent by this test")
    }
}
