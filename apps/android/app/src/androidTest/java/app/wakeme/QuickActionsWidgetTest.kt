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
            fun find(node: View): View? = if (node.contentDescription?.toString() == "Choose a friend to quack") node
                else (node as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            var button: View? = null
            val until = android.os.SystemClock.elapsedRealtime() + 20_000
            while (button == null && android.os.SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync { button = find(view) }
                if (button == null) Thread.sleep(200)
            }
            assertNotNull("toolbar rendered with an accessible friend action", button)
            DeviceEvidence.screenshot("53-home-widget-toolbar")
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { button!!.performClick() }
                val opened = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000)) { "widget opens the app" }
                assertEquals("friends", opened.intent.getStringExtra(MainActivity.EXTRA_ROUTE))
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            automation.dropShellPermissionIdentity()
            host.stopListening(); host.deleteHost()
        }
    }
}
