package app.wakeme.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

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
        val scheduler = AlarmEntryPoints.scheduler(context)
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            scheduler.rescheduleFromBootMirror()
            return
        }
        val pending = goAsync()
        AlarmEntryPoints.scope.launchCatching {
            try {
                scheduler.rescheduleAll()
                AlarmEntryPoints.readiness(context).reportCapabilities()
            } finally {
                pending.finish()
            }
        }
    }
}
