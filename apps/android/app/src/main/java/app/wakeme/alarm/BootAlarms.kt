package app.wakeme.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.wakeme.core.AlarmRole
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** No Room, account, network or credential-encrypted files may be opened on this path. */
class BootAlarms(private val context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("boot_alarms", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<BootAlarm> = prefs.getString("alarms", null)?.let {
        runCatching { json.decodeFromString(ListSerializer(BootAlarm.serializer()), it) }.getOrNull()
    }.orEmpty().filter { it.requestCode.toString() !in cancelled() }

    fun get(code: Int) = all().firstOrNull { it.requestCode == code }

    fun replace(alarms: List<BootAlarm>) {
        prefs.edit().putString("alarms", json.encodeToString(ListSerializer(BootAlarm.serializer()), alarms)).commit()
    }

    fun fireIntent(code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_REQUEST_CODE, code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun reschedule(now: Long = System.currentTimeMillis()) {
        if (!manager.canScheduleExactAlarms()) return
        for (alarm in all().filter { it.fireAt > now - 60_000 }) {
            // The alarm details are available before unlock; MainActivity/account UI is not.
            val show = PendingIntent.getActivity(context, alarm.requestCode, Intent(context, AlarmActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            runCatching { manager.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(alarm.fireAt, now + 1000), show), fireIntent(alarm.requestCode)) }
        }
    }

    /** Persist cancellation before first unlock; reconcile Room before it can re-arm these rows. */
    fun cancelFollowUp(planId: String, occurrenceKey: String) {
        val rows = all().filter { it.planId == planId && it.occurrenceKey == occurrenceKey && it.role == AlarmRole.FOLLOW_UP.name }
        val cancelled = cancelled() + rows.map { it.requestCode.toString() }
        prefs.edit().putStringSet("cancelled", cancelled).commit()
        rows.forEach { manager.cancel(fireIntent(it.requestCode)) }
    }

    fun cancelled(): Set<String> = prefs.getStringSet("cancelled", emptySet()).orEmpty().toSet()

    fun clearCancelled(codes: Set<String>) {
        prefs.edit().putStringSet("cancelled", cancelled() - codes).commit()
    }
}
