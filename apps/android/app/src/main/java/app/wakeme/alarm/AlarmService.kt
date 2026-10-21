package app.wakeme.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import androidx.core.app.NotificationCompat
import app.wakeme.R
import app.wakeme.core.AlarmRole
import app.wakeme.core.WakeSessionState
import app.wakeme.core.WakeCompletion
import app.wakeme.push.Channels
import app.wakeme.receipts.Receipt
import kotlinx.coroutines.runBlocking

/**
 * Plays an alarm that is already on this phone (a routine occurrence or an
 * immediate wake that passed local re-validation). Runs as a systemExempted
 * foreground service, which exact-alarm holders may use to continue an alarm.
 * Stopping the sound is always one tap and never needs the network. Ringing is
 * bounded by the accepted duration; volume is never escalated indefinitely.
 */
class AlarmService : Service() {
    private lateinit var audio: AlarmAudio
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStop = Runnable { stopSound(reason = "timeout") }

    override fun onCreate() {
        super.onCreate()
        audio = AlarmAudio(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(intent.getIntExtra(AlarmReceiver.EXTRA_REQUEST_CODE, 0))
            ACTION_STOP_SOUND -> stopSound(reason = "user")
            ACTION_COMPLETE -> finish(if (AlarmEntryPoints.sessions(this).active.value?.challengeRevisionId != null) WakeCompletion.SOLVED else WakeCompletion.CHECKED_IN)
            ACTION_CHECK_IN -> finish(WakeCompletion.CHECKED_IN)
            ACTION_ESCAPE -> finish(WakeCompletion.ESCAPED)
            ACTION_IM_UP -> imUp()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(requestCode: Int) {
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        val sessions = AlarmEntryPoints.sessions(this)
        // Before first unlock only the device-protected mirror is readable.
        val alarm = if (unlocked) runBlocking { AlarmEntryPoints.db(this@AlarmService).alarms().get(requestCode) }?.let {
            BootAlarm(it.requestCode, it.fireAt, it.soundId, it.ringSeconds, it.label, it.planId, it.commandId, it.role, it.occurrenceKey, it.version, it.challengeRevisionId)
        } else BootAlarms(this).get(requestCode)
        if (alarm == null) {
            stopSelf()
            return
        }
        if (BootAlarms(this).isEnded(alarm.planId)) { stopSelf(); return }
        val helper = if (unlocked) runBlocking { AlarmEntryPoints.db(this@AlarmService).alarms().get(requestCode)?.helperName } else null
        val challenge = alarm.challengeRevisionId
        val hasFollowUp = alarm.role == AlarmRole.MAIN.name && alarm.planId != null &&
            BootAlarms(this).all().any { it.planId == alarm.planId && it.role == AlarmRole.FOLLOW_UP.name && it.occurrenceKey == alarm.occurrenceKey }

        // systemExempted exists from Android 14; before that the untyped call is the supported form.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(alarm, helper), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification(alarm, helper))
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeMe:alarm").apply { acquire((alarm.ringSeconds + 30) * 1000L) }

        val report = audio.start(alarm.soundId)
        val previous = sessions.active.value?.takeIf { it.planId == alarm.planId && it.commandId == alarm.commandId && it.occurrenceKey == alarm.occurrenceKey && it.version == alarm.version }
        val isFollowUp = alarm.role == AlarmRole.FOLLOW_UP.name
        sessions.set(
            ActiveWake(
                requestCode = requestCode,
                planId = alarm.planId,
                commandId = alarm.commandId,
                occurrenceKey = alarm.occurrenceKey,
                version = alarm.version,
                label = alarm.label,
                helperName = helper ?: previous?.helperName,
                challengeRevisionId = challenge ?: previous?.challengeRevisionId,
                state = if (isFollowUp && previous != null) WakeSessionState.CHALLENGE else WakeSessionState.RINGING,
                hasFollowUp = hasFollowUp,
                ringing = report.started,
                routeType = report.routeType,
                playbackFailure = report.failure,
                startedAt = System.currentTimeMillis(),
                usedFallbackSound = report.usedFallbackSound,
            ),
        )
        startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, alarm.ringSeconds * 1000L)

        // Evidence: the app itself started (or failed to start) playback. This is not proof anyone heard it.
        val receipts = AlarmEntryPoints.receipts(this)
        if (alarm.commandId != null) {
            if (report.started) receipts.enqueue(Receipt.Command(alarm.commandId, "execution_reported"))
            else receipts.enqueue(Receipt.Command(alarm.commandId, "execution_refused", if (report.failure == "audio_focus_denied") "audio_focus_denied" else "os_scheduling_failed"))
        } else if (alarm.planId != null) {
            receipts.enqueue(Receipt.Occurrence(alarm.planId, alarm.occurrenceKey, alarm.version, if (isFollowUp) "follow_up_rang" else "rang"))
            // Arm the occurrence that just entered the window, so the routine keeps ringing
            // even if the app doesn't run again before then (ADR 0011).
            if (unlocked) AlarmEntryPoints.scope.launchCatching { AlarmEntryPoints.arming(this@AlarmService).topUpAll() }
        }
    }

    private fun stopSound(reason: String) {
        handler.removeCallbacks(autoStop)
        audio.stop()
        val sessions = AlarmEntryPoints.sessions(this)
        val active = sessions.active.value
        if (active != null) {
            val next = active.session.onSystemStop()
            sessions.set(active.copy(state = next.state, ringing = false))
            if (active.planId != null && reason == "user") {
                AlarmEntryPoints.receipts(this).enqueue(Receipt.Occurrence(active.planId, active.occurrenceKey, active.version, "stopped"))
            }
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (active?.hasFollowUp == true) postFollowUpReminder()
        stopSelf()
    }

    /** After a system-style stop with a follow-up pending, keep the challenge one tap away. */
    private fun postFollowUpReminder() {
        val open = PendingIntent.getActivity(this, 2, Intent(this, AlarmActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, Channels.ALARMS_QUIET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.alarm_start_challenge))
            .setContentText(getString(R.string.alarm_escape_explainer))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n)
    }

    /** Challenge completed or the safe escape used: cancel the follow-up, report, end the session. */
    private fun finish(outcome: WakeCompletion) {
        handler.removeCallbacks(autoStop)
        audio.stop()
        val sessions = AlarmEntryPoints.sessions(this)
        val active = sessions.active.value
        if (active != null) {
            val next = if (outcome != WakeCompletion.ESCAPED) active.session.onChallengeCompleted() else active.session.onEscape()
            if (next.cancelsFollowUp && active.planId != null) {
                if (getSystemService(UserManager::class.java).isUserUnlocked) {
                    runBlocking { AlarmEntryPoints.scheduler(this@AlarmService).cancelFollowUp(active.planId, active.occurrenceKey) }
                } else BootAlarms(this).cancelFollowUp(active.planId, active.occurrenceKey)
            }
            val receipts = AlarmEntryPoints.receipts(this)
            if (active.commandId != null) receipts.enqueue(Receipt.Command(active.commandId, outcome.commandReceipt))
            if (active.planId != null) receipts.enqueue(Receipt.Occurrence(active.planId, active.occurrenceKey, active.version, outcome.occurrenceOutcome))
            sessions.set(null)
        }
        releaseWakeLock()
        getSystemService(android.app.NotificationManager::class.java).cancel(NOTIFICATION_ID)
        getSystemService(android.app.NotificationManager::class.java).cancel(NOTIFICATION_ID + 1)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** No challenge attached: "I'm up" acknowledges and ends the session. */
    private fun imUp() {
        finish(WakeCompletion.CHECKED_IN)
    }

    private fun notification(alarm: BootAlarm, helper: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, AlarmActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AlarmService::class.java).setAction(ACTION_STOP_SOUND), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Channels.ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (alarm.label.isNotBlank()) alarm.label else getString(R.string.alarm_notification_title))
            .setContentText(helper?.let { getString(R.string.alarm_from_friend, it) } ?: getString(R.string.alarm_notification_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.alarm_stop_sound), stop)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        audio.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7001
        const val ACTION_RING = "app.wakeme.alarm.RING"
        const val ACTION_STOP_SOUND = "app.wakeme.alarm.STOP_SOUND"
        const val ACTION_COMPLETE = "app.wakeme.alarm.COMPLETE"
        const val ACTION_CHECK_IN = "app.wakeme.alarm.CHECK_IN"
        const val ACTION_ESCAPE = "app.wakeme.alarm.ESCAPE"
        const val ACTION_IM_UP = "app.wakeme.alarm.IM_UP"

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
