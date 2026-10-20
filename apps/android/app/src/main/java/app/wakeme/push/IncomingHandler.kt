package app.wakeme.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.wakeme.MainActivity
import app.wakeme.R
import app.wakeme.alarm.AlarmScheduler
import app.wakeme.alarm.ScheduleResult
import app.wakeme.core.Capability
import app.wakeme.core.GrantSnapshot
import app.wakeme.core.IncomingCommand
import app.wakeme.core.LocalDecision
import app.wakeme.core.evaluateLocalExecution
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.Prefs
import app.wakeme.data.local.ProcessedCommandEntity
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.receipts.Receipt
import app.wakeme.receipts.ReceiptQueue
import app.wakeme.sync.ChallengeInstaller
import app.wakeme.sync.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles data-only pushes. Nothing plays or shows until the command has
 * passed local re-validation against this phone's own cached grant, so a
 * revoked or narrowed permission wins even if the server dispatched first.
 * Expired commands are never replayed, and duplicates are no-ops.
 */
@Singleton
class IncomingHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LocalDb,
    private val prefs: Prefs,
    private val receipts: ReceiptQueue,
    private val scheduler: AlarmScheduler,
    private val challenges: ChallengeInstaller,
    private val focus: app.wakeme.focus.FocusStore,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    suspend fun handle(data: Map<String, String>) {
        when (data["t"]) {
            "command" -> handleCommand(data)
            "sync" -> SyncWorker.runNow(context)
            else -> showGeneric(data)
        }
    }

    private suspend fun handleCommand(data: Map<String, String>) {
        val me = prefs.currentMe() ?: return
        val id = data["id"] ?: return
        val cmd = IncomingCommand(
            id = id,
            capability = data["cap"] ?: return,
            actorId = data["actor"] ?: return,
            grantVersion = data["gv"]?.toIntOrNull() ?: return,
            expiresAt = data["exp"] ?: return,
            soundId = data["snd"],
            ringSeconds = data["ring"]?.toIntOrNull(),
        )
        val grant = db.grants().live(grantorId = me.id, granteeId = cmd.actorId)?.let { WakeMeApiJson.decodeFromString(GrantSnapshot.serializer(), it.json) }
        val (settings, zone) = prefs.recipientSettings()
        val seen = db.processed().seen(id)
        val decision = evaluateLocalExecution(Instant.now(), cmd, grant, zone, settings, seen)
        db.processed().insert(ProcessedCommandEntity(id, System.currentTimeMillis(), decision.toString()))

        when (decision) {
            is LocalDecision.Skip -> {
                if (decision.reason == "duplicate") return
                receipts.enqueue(Receipt.Command(id, "execution_refused", decision.reason))
                if (decision.reason == "needs_sync") SyncWorker.runNow(context)
            }
            is LocalDecision.Execute -> {
                receipts.enqueue(Receipt.Command(id, "received"))
                val title = data["title"] ?: ""
                when (cmd.capability) {
                    Capability.ALARM_REQUEST -> {
                        val challengeId = data["ch"]?.let { ch -> withTimeoutOrNull(6000) { challenges.ensureInstalled(ch) }?.let { ch } }
                        when (scheduler.scheduleImmediate(id, cmd.soundId ?: "bundled:rise.gentle", cmd.ringSeconds ?: 60, title.substringBefore(" is waking"), challengeId)) {
                            is ScheduleResult.Scheduled -> receipts.enqueue(Receipt.Command(id, "installed"))
                            else -> {
                                // Exact alarms not allowed: fall back to a high-priority notification and say so honestly.
                                notify(id, Channels.quackChannel("bundled:bell.bright"), data, actions = emptyList())
                                receipts.enqueue(Receipt.Command(id, "execution_refused", "permission_missing"))
                            }
                        }
                    }
                    Capability.NUDGE -> {
                        notify(id, if (decision.mode == "silent") Channels.REQUESTS_SILENT else Channels.quackChannel(cmd.soundId), data, actions = listOf(NotificationActionReceiver.ACK))
                        receipts.enqueue(Receipt.Command(id, "execution_reported"))
                    }
                    Capability.FOCUS -> {
                        // A request only: nothing starts until the recipient taps Start (ADR 0014).
                        val friend = (data["title"] ?: "").substringBefore(" asks you to focus").ifBlank { "A friend" }
                        val minutes = data["min"]?.toIntOrNull() ?: 25
                        focus.offer(app.wakeme.focus.FocusRequest(id, cmd.actorId, friend, minutes, Instant.parse(cmd.expiresAt).toEpochMilli()))
                        notify(id, if (decision.mode == "silent") Channels.REQUESTS_SILENT else Channels.REQUESTS, data, actions = emptyList(), route = "focus")
                        receipts.enqueue(Receipt.Command(id, "execution_reported"))
                    }
                    Capability.CHECKIN -> {
                        notify(id, if (decision.mode == "silent") Channels.REQUESTS_SILENT else Channels.REQUESTS, data, actions = listOf(NotificationActionReceiver.IM_OK))
                        receipts.enqueue(Receipt.Command(id, "execution_reported"))
                    }
                    else -> {
                        notify(id, if (decision.mode == "silent") Channels.REQUESTS_SILENT else Channels.REQUESTS, data, actions = emptyList())
                        receipts.enqueue(Receipt.Command(id, "execution_reported"))
                    }
                }
            }
        }
    }

    /** Renders localized copy from the server's key and arguments; English fallback from the payload. */
    private fun text(data: Map<String, String>, keyField: String, fallbackField: String): String {
        val args = data["locArgs"]?.let { runCatching { WakeMeApiJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        val key = data[keyField]?.replace('.', '_')
        val resId = key?.let { context.resources.getIdentifier(it, "string", context.packageName) } ?: 0
        return if (resId != 0) runCatching { context.getString(resId, *args.toTypedArray()) }.getOrDefault(data[fallbackField].orEmpty()) else data[fallbackField].orEmpty()
    }

    private fun notify(id: String, channel: String, data: Map<String, String>, actions: List<String>, route: String = "command/$id") {
        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(data, "titleLocKey", "title"))
            .setContentText(text(data, "locKey", "body"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setTimeoutAfter(Instant.parse(data["exp"] ?: Instant.now().plusSeconds(300).toString()).toEpochMilli() - System.currentTimeMillis() + 10 * 60_000)
        for (a in actions) b.addAction(0, context.getString(if (a == NotificationActionReceiver.ACK) R.string.action_got_it else R.string.action_im_ok), NotificationActionReceiver.intent(context, a, id))
        nm.notify(id.hashCode(), b.build())
    }

    private fun showGeneric(data: Map<String, String>) {
        val channel = when (data["t"]) {
            "safety" -> Channels.SAFETY
            "message" -> Channels.MESSAGES
            else -> Channels.UPDATES
        }
        val route = when (data["t"]) {
            "safety" -> "safety/${data["id"].orEmpty()}"
            "message" -> "conversation/${data["id"].orEmpty()}"
            "plan" -> "routine/${data["id"].orEmpty()}"
            else -> "home"
        }
        val open = PendingIntent.getActivity(context, route.hashCode(), Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(data, "titleLocKey", "title"))
            .setContentText(text(data, "locKey", "body"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (channel == Channels.SAFETY) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(route.hashCode(), n)
        if (data["t"] == "plan") SyncWorker.runNow(context)
    }
}
