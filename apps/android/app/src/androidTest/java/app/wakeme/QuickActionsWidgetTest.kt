package app.wakeme

import android.app.UiAutomation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Matcher
import org.hamcrest.Matchers.sameInstance
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.wakeme.widget.QuickActionsReceiver
import app.wakeme.widget.QuickActionsWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A real AppWidgetHost inflates the Glance RemoteViews; no launcher or account data is required. */
class QuickActionsWidgetTest {
    @get:Rule val activity = ActivityTestRule(ComponentActivity::class.java)

    @Test fun theToolbarRendersAndAQuackActionOnlyOpensTheFriendPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = AppWidgetHost(context, 903)
        val manager = AppWidgetManager.getInstance(context)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val id = host.allocateAppWidgetId()
        try {
            automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
            }
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, QuickActionsReceiver::class.java), options))
            automation.dropShellPermissionIdentity()
            lateinit var view: AppWidgetHostView
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(activity.activity, id, requireNotNull(manager.getAppWidgetInfo(id)))
                val density = context.resources.displayMetrics.density
                activity.activity.setContentView(view, ViewGroup.LayoutParams((320 * density).toInt(), (180 * density).toInt()))
            }
            runBlocking { QuickActionsWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(id)) }
            var button: View? = null
            val until = android.os.SystemClock.elapsedRealtime() + 20_000
            while (button == null && android.os.SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync { button = findLabel(view, FRIEND_ACTION) }
                if (button == null) Thread.sleep(200)
            }
            assertNotNull("toolbar rendered with an accessible friend action", button)
            DeviceEvidence.screenshot("53-home-widget-toolbar")
            onView(isRoot()).inRoot(withDecorView(sameInstance(activity.activity.window.decorView)))
                .perform(waitAndTapWidgetAction(FRIEND_ACTION))
            var opened: MainActivity? = null
            // Room for one cold start on a slow emulator. A launch that never came at all
            // (android-device #94, #102) was a tap lost to a widget reset; see WidgetKeepAlive.
            val launchDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (opened == null && android.os.SystemClock.elapsedRealtime() < launchDeadline) {
                instrumentation.runOnMainSync {
                    opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                }
                if (opened == null) Thread.sleep(200)
            }
            if (opened?.intent?.getStringExtra(MainActivity.EXTRA_ROUTE) != "friends") {
                // Name what was resumed and with which route, so the CI log alone explains it.
                var resumed = emptyList<String>()
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .map { "${it.javaClass.simpleName}(route=${it.intent?.getStringExtra(MainActivity.EXTRA_ROUTE)})" }
                }
                throw AssertionError("widget opens the app on friends; resumed after the tap: $resumed")
            }
        } finally {
            automation.dropShellPermissionIdentity()
            host.stopListening(); host.deleteHost()
        }
    }

    private fun findLabel(node: View, label: String): View? =
        if (node.contentDescription?.toString() == label) node
        else (node as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { findLabel(group.getChildAt(it), label) }
        }

    /**
     * Glance can replace its RemoteViews after the first render. Resolve the live action inside
     * one Espresso action, after draining main-thread updates, rather than matching a descendant
     * that can disappear between the separate render wait, screenshot and Espresso lookup.
     * The labelled child is not clickable: its nearest clickable ancestor owns the PendingIntent.
     * Inject one actual pointer tap; do not retry taps or call performClick/PendingIntent directly.
     */
    private fun waitAndTapWidgetAction(label: String) = object : ViewAction {
        override fun getDescription() = "wait for the live widget action '$label' and tap it once"
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun perform(controller: UiController, root: View) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            do {
                controller.loopMainThreadUntilIdle()
                val labelled = findLabel(root, label)
                val target = labelled?.let { child ->
                    generateSequence(child) { it.parent as? View }
                        // The host's direct child is the widget-wide default layout. Its fallback
                        // click opens MAIN/LAUNCHER without our route (run 96, Android 16).
                        // Wait for a real Glance action below that boundary, never tap the fallback.
                        .takeWhile { it !is AppWidgetHostView && it.parent !is AppWidgetHostView }
                        .firstOrNull { it.isClickable }
                }
                if (target != null && target.isAttachedToWindow && target.isEnabled && isDisplayingAtLeast(90).matches(target)) {
                    DeviceEvidence.note("widget-action", "labelType=${labelled?.contentDescription?.javaClass?.name}, one pointer tap on the live clickable ancestor")
                    click().perform(controller, target)
                    return
                }
                controller.loopMainThreadForAtLeast(100)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            throw AssertionError("The rendered widget action '$label' never became attached, visible and clickable")
        }
    }

    private companion object { const val FRIEND_ACTION = "Choose a friend to quack" }
}
