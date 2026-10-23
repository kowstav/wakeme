package app.wakeme.alarm

import app.wakeme.core.InstalledRoutine
import app.wakeme.core.LOCAL_PLAN_PREFIX
import app.wakeme.core.WakeMeJson
import app.wakeme.core.isLocalPlan
import app.wakeme.data.PlanTerms
import app.wakeme.data.WakeMeApi
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.RoutineEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal wake-ups set before signing in (doc 02 §9 guest alarms, doc 03 §5 step 2). They are
 * stored and armed only on this phone, exactly like an installed routine, with no server and no
 * receipts. Signing in moves them to the account: each is created on the server first and only
 * then removed here, so a phone that is offline at sign-in keeps ringing the local copy.
 */
@Singleton
class LocalRoutines @Inject constructor(
    private val db: LocalDb,
    private val scheduler: AlarmScheduler,
) {
    /** Local wake-ups, each with whether alarms are armed for it on this phone right now. */
    val routines: Flow<List<Pair<InstalledRoutine, Boolean>>> = combine(db.routines().observe(), db.alarms().observe()) { routines, alarms ->
        val now = System.currentTimeMillis()
        val armed = alarms.filter { it.fireAt > now }.mapNotNull { it.planId }.toSet()
        routines.filter { isLocalPlan(it.planId) }.mapNotNull { decode(it.json) }.sortedBy { it.localTime }.map { it to (it.planId in armed) }
    }

    suspend fun save(terms: PlanTerms): ScheduleResult {
        val routine = InstalledRoutine(
            planId = LOCAL_PLAN_PREFIX + UUID.randomUUID(),
            version = 1,
            label = terms.label,
            localTime = terms.localTime,
            timeZone = terms.timeZone,
            timeZonePolicy = terms.timeZonePolicy,
            recurrence = terms.recurrence,
            soundId = terms.soundId,
            ringSeconds = terms.ringSeconds,
            challengeRevisionId = null, // challenges come from the account
            followUpDelaySeconds = null,
            paused = false,
        )
        db.routines().upsert(RoutineEntity(routine.planId, 1, WakeMeJson.encodeToString(InstalledRoutine.serializer(), routine), "", true, System.currentTimeMillis()))
        return scheduler.installRoutine(routine, helperName = null)
    }

    /** Local first: it stops ringing at once. */
    suspend fun delete(planId: String) {
        if (!isLocalPlan(planId)) return
        scheduler.cancelPlan(planId)
        db.routines().delete(planId)
    }

    /**
     * After signing in: creates each local wake-up as the person's own routine on the server, then
     * removes the local copy (sync installs the server version). Stops at the first failure and
     * keeps what wasn't moved, so nothing stops ringing; it's tried again at the next sign-in check.
     */
    suspend fun moveToAccount(api: WakeMeApi): Int {
        var moved = 0
        for (entity in db.routines().all().filter { isLocalPlan(it.planId) }) {
            val r = decode(entity.json) ?: continue
            val terms = PlanTerms(r.label, r.localTime, r.timeZone, r.timeZonePolicy, r.recurrence, r.soundId, r.ringSeconds, null, null)
            runCatching { api.createPlan(null, terms) }.getOrElse { return moved }
            delete(r.planId)
            moved++
        }
        return moved
    }

    private fun decode(json: String) = runCatching { WakeMeJson.decodeFromString(InstalledRoutine.serializer(), json) }.getOrNull()
}
