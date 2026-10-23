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

    private fun stored(): List<BootAlarm> = prefs.getString("alarms", null)?.let {
        runCatching { json.decodeFromString(ListSerializer(BootAlarm.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun all(): List<BootAlarm> = stored().filter { it.requestCode.toString() !in cancelled() && !isEnded(it.planId) }

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
        // Only installed future routine occurrences survive boot. A fresh remote request must
        // never become a replayable routine, and a past occurrence may already have been stopped.
        for (alarm in all().filter { it.commandId == null && it.fireAt > now }) {
            // The alarm details are available before unlock; MainActivity/account UI is not.
            val show = PendingIntent.getActivity(context, alarm.requestCode, Intent(context, AlarmActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            runCatching { manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.fireAt, show), fireIntent(alarm.requestCode)) }
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

    fun isEnded(planId: String?): Boolean = planId != null && planId in prefs.getStringSet("ended_plans", emptySet()).orEmpty()

    /** Local authority wins over stale sync/bootstrap data, including a restored server backup. */
    fun endPlan(planId: String, accountId: String) = synchronized(endLock) {
        val ended = prefs.getStringSet("ended_plans", emptySet()).orEmpty().toSet() + planId
        val pending = pendingEnds().toMutableMap().apply { put(planId, accountId) }
        prefs.edit().putStringSet("ended_plans", ended).putString("pending_ends", json.encodeToString(pending.toMap())).commit()
        stored().filter { it.planId == planId }.forEach { manager.cancel(fireIntent(it.requestCode)) }
    }

    private fun pendingEnds(): Map<String, String> = prefs.getString("pending_ends", null)?.let {
        runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
    }.orEmpty()

    /** Never send another account's queued mutation after switching accounts. */
    fun pendingEnds(accountId: String): Set<String> = synchronized(endLock) {
        pendingEnds().filterValues { it == accountId }.keys.toSet()
    }

    fun acknowledgeEnd(planId: String) = synchronized(endLock) {
        prefs.edit().putString("pending_ends", json.encodeToString(pendingEnds() - planId)).commit()
    }

    companion object { private val endLock = Any() }
}
