package app.wakeme.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.wakeme.sync.SyncWorker

/**
 * Fired by AlarmManager at the exact time. Exact alarms are an allowed
 * exception to background foreground-service start restrictions, so the
 * alarm service starts here. No network is touched on this path.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val code = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)
        val service = Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_RING).putExtra(EXTRA_REQUEST_CODE, code)
        ContextCompat.startForegroundService(context, service)
    }

    companion object {
        const val ACTION_FIRE = "app.wakeme.alarm.FIRE"
        const val EXTRA_REQUEST_CODE = "request_code"
    }
}

/** Re-registers alarms after reboot (including before first unlock), app update, time or zone change. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system broadcasts in the manifest (all protected, so only the system
        // sends them); an explicit intent from another app with any other action is ignored.
        if (intent.action !in SYSTEM_ACTIONS) return
        val scheduler = AlarmEntryPoints.scheduler(context)
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            scheduler.rescheduleFromBootMirror()
            return
        }
        val pending = goAsync()
        val clockChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        AlarmEntryPoints.scope.launchCatching {
            try {
                scheduler.rescheduleAll(recompute = clockChanged)
                AlarmEntryPoints.readiness(context).reportCapabilities()
                // Exact alarms allowed again: routines that failed to install meanwhile come from the server.
                if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) SyncWorker.runNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The protected system broadcasts RescheduleReceiver is declared for (AndroidManifest.xml). */
private val SYSTEM_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_LOCKED_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)
