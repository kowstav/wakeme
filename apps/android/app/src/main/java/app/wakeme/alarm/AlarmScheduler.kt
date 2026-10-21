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
    /** Signed out while installing: nothing was left armed. */
    data object Abandoned : ScheduleResult()
}

/** Minimal copy kept in device-protected storage so alarms re-register after reboot, before first unlock. */
@Serializable
data class BootAlarm(val requestCode: Int, val fireAt: Long, val soundId: String, val ringSeconds: Int, val label: String, val planId: String?, val commandId: String?, val role: String, val occurrenceKey: String, val version: Int, val challengeRevisionId: String? = null)

/**
 * Installs native alarms with AlarmManager.setAlarmClock: exact, allowed in
 * Doze, shown to the user as an upcoming alarm, and an allowed start for the
 * alarm foreground service. Never uses WorkManager as the precision clock.
 */
@Singleton
class AlarmScheduler @Inject constructor(@ApplicationContext private val context: Context, private val db: LocalDb) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val boot = BootAlarms(context)
    private val json = Json { ignoreUnknownKeys = true }

    /** Bumped by [cancelAll] (sign-out): installs that started before it must not leave alarms behind. */
    @Volatile private var generation = 0

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    suspend fun installRoutine(routine: InstalledRoutine, helperName: String?, now: Instant = Instant.now()): ScheduleResult {
        if (boot.isEnded(routine.planId)) return ScheduleResult.Abandoned
        val started = generation
        cancelPlan(routine.planId)
        val alarms = scheduleFor(routine, now, ZoneId.systemDefault())
        if (alarms.isEmpty()) return ScheduleResult.NothingToSchedule
        if (!canScheduleExact()) return ScheduleResult.ExactAlarmPermissionMissing
        return try {
            for (a in alarms) register(entity(a, routine, helperName))
            if (abandoned(routine.planId, started)) ScheduleResult.Abandoned else ScheduleResult.Scheduled(alarms)
        } catch (e: SecurityException) {
            ScheduleResult.ExactAlarmPermissionMissing
        }
    }

    /**
     * Arms the occurrences of an installed routine that entered the window since it was
     * installed (ADR 0011). Alarms already armed are re-registered as they are (the same
     * PendingIntent replaces itself), so a follow-up still pending for an occurrence that
     * just rang is kept, and one cancelled by completing the challenge isn't brought back.
     * Local data only. Returns true when alarms were added.
     */
    suspend fun topUp(routine: InstalledRoutine, helperName: String?, now: Instant = Instant.now()): Boolean {
        if (boot.isEnded(routine.planId)) return false
        val started = generation
        if (!canScheduleExact()) return false
        val rows = db.alarms().forPlan(routine.planId)
        val known = rows.map { it.requestCode }.toSet()
        val missing = scheduleFor(routine, now, ZoneId.systemDefault()).filter { it.requestCode !in known }
        return try {
            for (row in rows.filter { it.fireAt > now.toEpochMilli() }) setAlarmClock(row)
            for (a in missing) register(entity(a, routine, helperName))
            missing.isNotEmpty() && !abandoned(routine.planId, started)
        } catch (e: SecurityException) {
            false
        }
    }

    /** Signed out while this plan's alarms were being registered: remove them again. */
    private suspend fun abandoned(planId: String, started: Int): Boolean {
        if (generation == started && !boot.isEnded(planId)) return false
        cancelPlan(planId)
        return true
    }

    /** Alarms still ahead for a routine, soonest first. */
    suspend fun armed(planId: String, now: Instant = Instant.now()): List<ScheduledAlarmEntity> =
        if (boot.isEnded(planId)) emptyList() else db.alarms().forPlan(planId).filter { it.fireAt > now.toEpochMilli() }.sortedBy { it.fireAt }

    /** Rows of alarms that rang more than a day ago are bookkeeping only. */
    suspend fun pruneRang(now: Instant = Instant.now()) {
        val before = now.toEpochMilli() - 24 * 60 * 60 * 1000L
        val old = db.alarms().all().filter { it.fireAt < before }
        for (a in old) db.alarms().delete(a.requestCode)
        if (old.isNotEmpty()) mirrorForBoot()
    }

    private fun entity(a: ScheduledAlarm, routine: InstalledRoutine, helperName: String?) = ScheduledAlarmEntity(
        requestCode = a.requestCode, planId = a.planId, commandId = null, version = a.version, occurrenceKey = a.occurrenceKey,
        role = a.role.name, fireAt = a.fireAt.toEpochMilli(), soundId = routine.soundId, ringSeconds = routine.ringSeconds,
        label = routine.label, helperName = helperName, challengeRevisionId = routine.challengeRevisionId,
    )

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
        if (boot.isEnded(entity.planId)) return
        setAlarmClock(entity)
        db.alarms().upsert(entity)
        // End may have raced an in-flight install. Keep both the mirror and DB from reviving it.
        if (boot.isEnded(entity.planId)) { cancel(entity.requestCode); return }
        mirrorForBoot()
    }

    private fun setAlarmClock(entity: ScheduledAlarmEntity) {
        if (boot.isEnded(entity.planId)) return
        val show = PendingIntent.getActivity(context, entity.requestCode, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(entity.fireAt, show), firePendingIntent(entity.requestCode))
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
        generation++
        for (a in db.alarms().all()) alarmManager.cancel(firePendingIntent(a.requestCode))
        db.alarms().clear()
        mirrorForBoot()
    }

    /**
     * After reboot, an app update, a time or zone change, or exact alarms being allowed again:
     * re-register from local data only (no network needed). A clock or zone change recomputes
     * every occurrence; otherwise armed alarms are kept as they are and the window topped up.
     */
    suspend fun rescheduleAll(recompute: Boolean) {
        reconcileBootCancellations()
        val now = System.currentTimeMillis()
        // A clock/reboot/update recovery is for installed routines. Never replay a fresh command.
        for (a in db.alarms().all().filter { it.commandId != null }) cancel(a.requestCode)
        for (a in db.alarms().all()) {
            if (a.fireAt < now - 60_000) db.alarms().delete(a.requestCode)
        }
        for (r in db.routines().all()) {
            val routine = json.decodeFromString(InstalledRoutine.serializer(), r.json)
            val helper = if (r.ownerIsCreator) null else r.creatorName
            if (recompute) installRoutine(routine, helper) else topUp(routine, helper)
        }
    }

    /** Pre-unlock path: credential-encrypted Room isn't readable yet, so use the device-protected mirror. */
    fun rescheduleFromBootMirror() {
        boot.reschedule()
    }

    fun bootAlarm(requestCode: Int): BootAlarm? {
        return boot.get(requestCode)
    }

    suspend fun reconcileBootCancellations() {
        val codes = boot.cancelled()
        for (code in codes) code.toIntOrNull()?.let { db.alarms().delete(it) }
        if (codes.isNotEmpty()) {
            mirrorForBoot()
            boot.clearCancelled(codes)
        }
    }

    private suspend fun mirrorForBoot() {
        val list = db.alarms().all().map { BootAlarm(it.requestCode, it.fireAt, it.soundId, it.ringSeconds, it.label, it.planId, it.commandId, it.role, it.occurrenceKey, it.version, it.challengeRevisionId) }
        boot.replace(list)
    }
}
