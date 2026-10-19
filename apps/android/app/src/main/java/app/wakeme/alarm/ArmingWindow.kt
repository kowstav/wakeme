package app.wakeme.alarm

import app.wakeme.core.AlarmRole
import app.wakeme.core.InstalledRoutine
import app.wakeme.core.WakeMeJson
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.Prefs
import app.wakeme.receipts.Receipt
import app.wakeme.receipts.ReceiptQueue
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every installed routine armed over the rolling window (ADR 0011) and tells the
 * server what is armed, so readiness stays honest. Runs when an alarm rings and before each
 * sync. Needs no network: receipts wait in the queue.
 */
@Singleton
class ArmingWindow @Inject constructor(
    private val db: LocalDb,
    private val scheduler: AlarmScheduler,
    private val receipts: ReceiptQueue,
    private val prefs: Prefs,
) {
    suspend fun topUpAll(now: Instant = Instant.now()) {
        scheduler.pruneRang(now)
        for (r in db.routines().all()) {
            val routine = runCatching { WakeMeJson.decodeFromString(InstalledRoutine.serializer(), r.json) }.getOrNull() ?: continue
            if (scheduler.topUp(routine, if (r.ownerIsCreator) null else r.creatorName, now)) report(routine, now)
        }
    }

    /** Installation receipt with everything armed for the routine from now on. */
    suspend fun report(routine: InstalledRoutine, now: Instant = Instant.now()) {
        val armed = scheduler.armed(routine.planId, now)
        val next = armed.firstOrNull { it.role == AlarmRole.MAIN.name }?.fireAt?.let(Instant::ofEpochMilli)
        val instants = armed.map { Instant.ofEpochMilli(it.fireAt).toString() }
        receipts.enqueue(Receipt.Installation(routine.planId, routine.version, "scheduled", next?.toString(), instants, prefs.installError(routine.planId)))
    }
}
