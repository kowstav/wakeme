package app.wakeme.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.wakeme.alarm.AlarmScheduler
import app.wakeme.alarm.ArmingWindow
import app.wakeme.alarm.ScheduleResult
import app.wakeme.core.AlarmRole
import app.wakeme.core.ChallengeEnvelopes
import app.wakeme.core.InstalledRoutine
import app.wakeme.core.SignedEnvelope
import app.wakeme.core.WakeMeJson
import app.wakeme.core.installsRoutines
import app.wakeme.data.device.DeviceIdentity
import app.wakeme.data.local.GrantEntity
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.Prefs
import app.wakeme.data.local.RevisionEntity
import app.wakeme.data.local.RoutineEntity
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.GrantDto
import app.wakeme.data.net.GrantsDto
import app.wakeme.data.net.MeDto
import app.wakeme.data.net.PlanDto
import app.wakeme.data.net.PlansDto
import app.wakeme.data.net.PublicConfigDto
import app.wakeme.data.net.SignedEnvelopeDto
import app.wakeme.data.net.SyncDto
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.receipts.Receipt
import app.wakeme.receipts.ReceiptQueue
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_LIST_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/** The server returns at most this many grants per direction (`GET /v1/grants`). */
private const val GRANT_LIST_LIMIT = 200

/** Downloads, verifies and stores signed challenge revisions for offline use. */
@Singleton
class ChallengeInstaller @Inject constructor(private val api: ApiClient, private val db: LocalDb, private val prefs: Prefs) {
    /**
     * The server's published key list, cached for a day so a key removed after a
     * compromise stops being trusted. [refresh] forces a fetch; when a routine
     * refresh fails, the cached list is used.
     */
    suspend fun trustedKeys(refresh: Boolean = false): Map<String, String> {
        val cached = prefs.trustedChallengeKeys()
        val stale = System.currentTimeMillis() - prefs.trustedChallengeKeysAt() > KEY_LIST_MAX_AGE_MS
        if (cached.isNotEmpty() && !stale && !refresh) return cached
        val keys = try {
            api.get("/v1/config", PublicConfigDto.serializer()).challengeSigningKeys.associate { it.keyId to it.spki }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached.isNotEmpty() && !refresh) return cached
            throw e
        }
        prefs.setTrustedChallengeKeys(keys)
        return keys
    }

    /** Returns true when the revision is available locally and its signature verified. */
    suspend fun ensureInstalled(revisionId: String): Boolean {
        if (db.revisions().get(revisionId) != null) return true
        val env = api.get("/v1/challenge-revisions/$revisionId", SignedEnvelopeDto.serializer())
        // After a signing-key rotation the cached list lacks the new key ID: refresh once.
        // Revisions installed earlier were verified at install time and stay installed.
        val keys = trustedKeys().let { if (env.keyId in it) it else trustedKeys(refresh = true) }
        val payload = ChallengeEnvelopes.verify(SignedEnvelope(env.revisionId, env.payload, env.signature, env.keyId, env.digest, env.alg), keys)
        db.revisions().upsert(RevisionEntity(revisionId, WakeMeJson.encodeToString(app.wakeme.core.ChallengeDefinition.serializer(), payload.definition), System.currentTimeMillis()))
        return true
    }
}

/**
 * Converges this phone with the server. Push is only a hint: the durable
 * cursor feed is the source of truth, so a phone that missed every push still
 * catches up. Applying a routine installs native alarms locally and then
 * reports exactly what the OS scheduler accepted.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: ApiClient,
    private val db: LocalDb,
    private val prefs: Prefs,
    private val scheduler: AlarmScheduler,
    private val receipts: ReceiptQueue,
    private val challenges: ChallengeInstaller,
    private val device: DeviceIdentity,
    private val arming: ArmingWindow,
) {
    private val mutex = Mutex()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Entity names that changed, for screens to refresh. */
    val changes: SharedFlow<String> = _changes

    suspend fun sync() = mutex.withLock {
        // Local first: keep routines armed over the window even when the server can't be reached.
        arming.topUpAll()
        val cursor = prefs.cursor()
        if (cursor == null) return@withLock bootstrap()
        var next: String = cursor
        while (true) {
            val page = api.get("/v1/sync?cursor=" + URLEncoder.encode(next, "UTF-8"), SyncDto.serializer())
            if (page.resetRequired == true) {
                bootstrap()
                return@withLock
            }
            for (c in page.changes) apply(c.entity, c.id, c.op, c.data)
            next = page.cursor
            prefs.setCursor(next)
            if (!page.hasMore) break
        }
    }

    private suspend fun bootstrap() {
        val head = api.get("/v1/sync", SyncDto.serializer()).cursor
        val me = api.get("/v1/me", MeDto.serializer())
        prefs.saveMe(me)
        val listed = mutableSetOf<String>()
        var complete = true
        for (dir in listOf("given", "received")) {
            val grants = api.get("/v1/grants?direction=$dir&includeRevoked=true", GrantsDto.serializer()).grants
            if (grants.size >= GRANT_LIST_LIMIT) complete = false
            for (g in grants) { storeGrant(g); listed += g.id }
        }
        // After a server restore, a grant this phone saw may be gone: it must not stay live here.
        if (complete) for (id in db.grants().liveIds()) if (id !in listed) db.grants().markRevoked(id)
        reinstallAll(me)
        prefs.setCursor(head)
        _changes.tryEmit("all")
    }

    private suspend fun apply(entity: String, id: String, op: String, data: kotlinx.serialization.json.JsonElement?) {
        when (entity) {
            "grant" -> data?.let { storeGrant(WakeMeApiJson.decodeFromJsonElement<GrantDto>(it)) }
            "me" -> data?.let {
                val before = prefs.currentMe()
                val me = WakeMeApiJson.decodeFromJsonElement<MeDto>(it)
                prefs.saveMe(me)
                // The primary phone changed: move the alarms so exactly one phone rings.
                if (before != null && isPrimary(before) != isPrimary(me)) reinstallAll(me)
            }
            "alarm_plan" -> {
                val me = prefs.currentMe() ?: return
                if (data != null) applyPlan(WakeMeApiJson.decodeFromJsonElement<PlanDto>(data), me.id)
            }
        }
        _changes.tryEmit(entity)
    }

    private suspend fun storeGrant(g: GrantDto) {
        db.grants().upsert(GrantEntity(g.id, g.grantorId, g.granteeId, g.version, g.revokedAt != null, WakeMeApiJson.encodeToString(GrantDto.serializer(), g)))
    }

    /** Install, replace or remove the native alarms for one routine owned by this user. */
    /**
     * Wake alarms ring only on the owner's primary phone; other signed-in devices
     * get passive updates. No primary yet (first registration in progress) counts
     * as primary, so a single phone never silently skips its alarms.
     */
    private suspend fun isPrimary(me: MeDto): Boolean = installsRoutines(me.primaryDeviceId, device.deviceId())

    private suspend fun reinstallAll(me: MeDto) {
        val plans = api.get("/v1/alarm-plans?includeEnded=true", PlansDto.serializer())
        for (p in plans.mine) applyPlan(p, me.id, me)
        // Routines are stored only from server data, so one the server no longer lists
        // (possible only after a server restore) stops ringing here.
        val listed = plans.mine.map { it.id }.toSet()
        for (r in db.routines().all()) if (r.planId !in listed) {
            scheduler.cancelPlan(r.planId)
            db.routines().delete(r.planId)
        }
    }

    private suspend fun applyPlan(plan: PlanDto, myId: String, me: MeDto? = null) {
        if (plan.owner.id != myId) return
        val current = me ?: prefs.currentMe()
        if (current != null && !isPrimary(current)) {
            // Not the ringing phone: remove any local copy silently; the primary reports installation.
            if (db.routines().get(plan.id) != null) {
                scheduler.cancelPlan(plan.id)
                db.routines().delete(plan.id)
            }
            return
        }
        val accepted = plan.accepted
        val active = plan.state == "active" && accepted != null
        if (!active) {
            if (db.routines().get(plan.id) != null) {
                scheduler.cancelPlan(plan.id)
                db.routines().delete(plan.id)
                receipts.enqueue(Receipt.Installation(plan.id, accepted?.version ?: plan.pending?.version ?: 1, "removed", null, emptyList(), null))
            }
            return
        }
        val existing = db.routines().get(plan.id)
        val sameVersion = existing?.version == accepted!!.version
        val challengeOk = accepted.challengeRevisionId?.let { runCatching { challenges.ensureInstalled(it) }.getOrDefault(false) } ?: true
        val routine = InstalledRoutine(
            planId = plan.id, version = accepted.version, label = accepted.label, localTime = accepted.localTime, timeZone = accepted.timeZone,
            timeZonePolicy = accepted.timeZonePolicy, recurrence = accepted.recurrence, soundId = accepted.soundId, ringSeconds = accepted.ringSeconds,
            challengeRevisionId = if (challengeOk) accepted.challengeRevisionId else null, followUpDelaySeconds = accepted.followUpDelaySeconds, paused = false,
        )
        val helper = if (plan.creator.id != myId) plan.creator.displayName else null
        val errorCode = if (challengeOk) null else "challenge_unavailable"
        val ready = plan.readiness == "ready" || plan.readiness == "ready_edit_pending"
        val installed = existing?.let { runCatching { WakeMeJson.decodeFromString(InstalledRoutine.serializer(), it.json) }.getOrNull() }
        if (installed == routine) {
            // Already installed as is: only arm what entered the window. Installing again would
            // drop a follow-up still pending for an occurrence that just rang (reporting "rang"
            // changes the routine on the server, which brings it back here).
            val added = scheduler.topUp(routine, helper)
            val next = scheduler.armed(plan.id).firstOrNull { it.role == AlarmRole.MAIN.name }?.fireAt?.let(java.time.Instant::ofEpochMilli)
            val reportedNext = plan.installation?.nextFireAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if (next != null && (added || !ready || reportedNext != next)) {
                prefs.setInstallError(plan.id, errorCode)
                arming.report(routine)
            }
            return
        }
        // Replace by installing the new version first, then the cancellation of old alarms happens inside installRoutine.
        when (val result = scheduler.installRoutine(routine, helper)) {
            is ScheduleResult.Scheduled -> {
                db.routines().upsert(RoutineEntity(plan.id, routine.version, WakeMeJson.encodeToString(InstalledRoutine.serializer(), routine), plan.creator.displayName, plan.creator.id == myId, System.currentTimeMillis()))
                val main = result.alarms.firstOrNull()
                val reportedNext = plan.installation?.nextFireAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                if (!sameVersion || !ready || reportedNext != main?.fireAt) {
                    prefs.setInstallError(plan.id, errorCode)
                    receipts.enqueue(Receipt.Installation(plan.id, routine.version, "scheduled", main?.fireAt?.toString(), result.alarms.map { it.fireAt.toString() }, errorCode))
                }
            }
            ScheduleResult.NothingToSchedule -> {
                db.routines().upsert(RoutineEntity(plan.id, routine.version, WakeMeJson.encodeToString(InstalledRoutine.serializer(), routine), plan.creator.displayName, plan.creator.id == myId, System.currentTimeMillis()))
            }
            // Nothing is armed for it now; drop the local copy so a top-up can't arm an older version.
            ScheduleResult.ExactAlarmPermissionMissing -> {
                db.routines().delete(plan.id)
                receipts.enqueue(Receipt.Installation(plan.id, routine.version, "failed", null, emptyList(), "exact_alarm_permission_missing"))
            }
            is ScheduleResult.Failed -> {
                db.routines().delete(plan.id)
                receipts.enqueue(Receipt.Installation(plan.id, routine.version, "failed", null, emptyList(), result.reason))
            }
            ScheduleResult.Abandoned -> Unit
        }
    }

    suspend fun clearLocal() {
        scheduler.cancelAll()
        db.routines().clear()
        db.grants().clear()
        prefs.setCursor(null)
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        engine.sync()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        /** Periodic catch-up; never used as an alarm clock. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
