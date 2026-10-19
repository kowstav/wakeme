package app.wakeme.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.wakeme.alarm.AlarmEntryPoints
import app.wakeme.alarm.launchCatching
import app.wakeme.receipts.Receipt
import app.wakeme.sync.DeviceRegistrar
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class WakeMeMessagingService : FirebaseMessagingService() {
    @Inject lateinit var handler: IncomingHandler
    @Inject lateinit var registrar: DeviceRegistrar

    /**
     * High-priority FCM grants a short processing window; work is bounded so
     * it finishes inside it. Anything slower (receipts, sync) is handed to
     * WorkManager.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { withTimeoutOrNull(9_000) { handler.handle(message.data) } }
    }

    override fun onNewToken(token: String) {
        AlarmEntryPoints.scope.launchCatching { registrar.updatePushToken(token) }
    }
}

/** Notification actions: "Got it" (acknowledge a quack) and "I'm OK" (answer a check-in). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        AlarmEntryPoints.receipts(context).enqueue(Receipt.Command(id, "acknowledged"))
        context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
    }

    companion object {
        const val ACK = "ack"
        const val IM_OK = "im_ok"
        private const val EXTRA_ID = "command_id"

        fun intent(context: Context, action: String, commandId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (action + commandId).hashCode(),
                Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, commandId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
