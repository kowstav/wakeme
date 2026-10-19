package app.wakeme.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.wakeme.MainActivity
import app.wakeme.core.AlarmRole
import app.wakeme.core.InstalledRoutine
import app.wakeme.core.ScheduledAlarm
import app.wakeme.core.scheduleFor
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.ScheduledAlarmEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScheduleResult {
    data class Scheduled(val alarms: List<ScheduledAlarm>) : ScheduleResult()
    data object NothingToSchedule : ScheduleResult()
    /** The user hasn't granted "Alarms & reminders"; the routine is saved but NOT armed. */
    data object ExactAlarmPermissionMissing : ScheduleResult()
    data class Failed(val reason: String) : ScheduleResult()
}

/** Minimal copy kept in device-protected storage so alarms re-register after reboot, before first unlock. */
@Serializable
data class BootAlarm(val requestCode: Int, val fireAt: Long, val soundId: String, val ringSeconds: Int, val label: String, val planId: String?, val commandId: String?, val role: String, val occurrenceKey: String, val version: Int)

/**
 * Installs native alarms with AlarmManager.setAlarmClock: exact, allowed in
 * Doze, shown to the user as an upcoming alarm, and an allowed start for the
 * alarm foreground service. Never uses WorkManager as the precision clock.
 */
@Singleton
class AlarmScheduler @Inject constructor(@ApplicationContext private val context: Context, private val db: LocalDb) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val bootPrefs = context.createDeviceProtectedStorageContext().getSharedPreferences("boot_alarms", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    suspend fun installRoutine(routine: InstalledRoutine, helperName: String?, now: Instant = Instant.now()): ScheduleResult {
        cancelPlan(routine.planId)
        val alarms = scheduleFor(routine, now, ZoneId.systemDefault())
        if (alarms.isEmpty()) return ScheduleResult.NothingToSchedule
        if (!canScheduleExact()) return ScheduleResult.ExactAlarmPermissionMissing
        return try {
            for (a in alarms) {
                val entity = ScheduledAlarmEntity(
                    requestCode = a.requestCode, planId = a.planId, commandId = null, version = a.version, occurrenceKey = a.occurrenceKey,
                    role = a.role.name, fireAt = a.fireAt.toEpochMilli(), soundId = routine.soundId, ringSeconds = routine.ringSeconds,
                    label = routine.label, helperName = helperName, challengeRevisionId = routine.challengeRevisionId,
                )
                register(entity)
            }
            ScheduleResult.Scheduled(alarms)
        } catch (e: SecurityException) {
            ScheduleResult.ExactAlarmPermissionMissing
        }
    }

    /** Immediate remote wake that already passed local re-validation: ring in ~1 second via the alarm path. */
    suspend fun scheduleImmediate(commandId: String, soundId: String, ringSeconds: Int, helperName: String, challengeRevisionId: String?): ScheduleResult {
        if (!canScheduleExact()) return ScheduleResult.ExactAlarmPermissionMissing
        val entity = ScheduledAlarmEntity(
            requestCode = ("cmd:$commandId").hashCode(), planId = null, commandId = commandId, version = 0, occurrenceKey = commandId,
            role = AlarmRole.MAIN.name, fireAt = System.currentTimeMillis() + 1000, soundId = soundId, ringSeconds = ringSeconds,
            label = "", helperName = helperName, challengeRevisionId = challengeRevisionId,
        )
        return try {
            register(entity)
            ScheduleResult.Scheduled(emptyList())
        } catch (e: SecurityException) {
            ScheduleResult.ExactAlarmPermissionMissing
        }
    }

    private suspend fun register(entity: ScheduledAlarmEntity) {
        val show = PendingIntent.getActivity(context, entity.requestCode, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(entity.fireAt, show), firePendingIntent(entity.requestCode))
        db.alarms().upsert(entity)
        mirrorForBoot()
    }

    private fun firePendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_REQUEST_CODE, requestCode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    suspend fun cancel(requestCode: Int) {
        alarmManager.cancel(firePendingIntent(requestCode))
        db.alarms().delete(requestCode)
        mirrorForBoot()
    }

    /** Local cancellation happens first and immediately; the server learns on next sync. */
    suspend fun cancelPlan(planId: String) {
        for (a in db.alarms().forPlan(planId)) cancel(a.requestCode)
    }

    suspend fun cancelFollowUp(planId: String, occurrenceKey: String) {
        db.alarms().forPlan(planId).filter { it.role == AlarmRole.FOLLOW_UP.name && it.occurrenceKey == occurrenceKey }.forEach { cancel(it.requestCode) }
    }

    suspend fun cancelAll() {
        for (a in db.alarms().all()) alarmManager.cancel(firePendingIntent(a.requestCode))
        db.alarms().clear()
        mirrorForBoot()
    }

    /** After reboot, time or zone change: re-register from local data only (no network needed). */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (a in db.alarms().all()) {
            if (a.fireAt < now - 60_000) db.alarms().delete(a.requestCode)
        }
        for (r in db.routines().all()) {
            val routine = json.decodeFromString(InstalledRoutine.serializer(), r.json)
            installRoutine(routine, if (r.ownerIsCreator) null else r.creatorName)
        }
        for (a in db.alarms().all().filter { it.commandId != null && it.fireAt >= now }) register(a)
    }

    /** Pre-unlock path: credential-encrypted Room isn't readable yet, so use the device-protected mirror. */
    fun rescheduleFromBootMirror() {
        val raw = bootPrefs.getString("alarms", null) ?: return
        val alarms = runCatching { json.decodeFromString(ListSerializer(BootAlarm.serializer()), raw) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        for (a in alarms.filter { it.fireAt > now - 60_000 }) {
            val show = PendingIntent.getActivity(context, a.requestCode, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            runCatching { alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(a.fireAt, now + 1000), show), firePendingIntent(a.requestCode)) }
        }
    }

    fun bootAlarm(requestCode: Int): BootAlarm? {
        val raw = bootPrefs.getString("alarms", null) ?: return null
        return runCatching { json.decodeFromString(ListSerializer(BootAlarm.serializer()), raw) }.getOrNull()?.firstOrNull { it.requestCode == requestCode }
    }

    private suspend fun mirrorForBoot() {
        val list = db.alarms().all().map { BootAlarm(it.requestCode, it.fireAt, it.soundId, it.ringSeconds, it.label, it.planId, it.commandId, it.role, it.occurrenceKey, it.version) }
        bootPrefs.edit().putString("alarms", json.encodeToString(ListSerializer(BootAlarm.serializer()), list)).apply()
    }
}
