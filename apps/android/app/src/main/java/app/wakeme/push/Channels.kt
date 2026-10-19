package app.wakeme.push

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import app.wakeme.R

/**
 * Notification channels. Android fixes a channel's sound at creation, so each
 * bundled quack gets its own channel; the user can still change or mute any
 * of them in system settings, which Wake Me respects.
 */
object Channels {
    const val ALARMS = "alarms"
    const val ALARMS_QUIET = "alarms_quiet"
    const val REQUESTS = "requests"
    const val REQUESTS_SILENT = "requests_silent"
    const val MESSAGES = "messages"
    const val SAFETY = "safety"
    const val UPDATES = "updates"
    private const val GROUP_FRIENDS = "friends"

    val quackSounds = mapOf(
        "bundled:quack.classic" to R.raw.quack_classic,
        "bundled:quack.double" to R.raw.quack_double,
        "bundled:chime.soft" to R.raw.chime_soft,
        "bundled:bell.bright" to R.raw.bell_bright,
        "bundled:rise.gentle" to R.raw.rise_gentle,
        "bundled:pulse.wake" to R.raw.pulse_wake,
    )

    fun quackChannel(soundId: String?): String = "quack_" + (if (soundId != null && soundId in quackSounds) soundId.removePrefix("bundled:").replace('.', '_') else "quack_classic")

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_FRIENDS, context.getString(R.string.tab_friends)))
        val notifAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        for ((id, res) in quackSounds) {
            nm.createNotificationChannel(
                NotificationChannel(quackChannel(id), context.getString(R.string.channel_quacks) + " · " + id.removePrefix("bundled:"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_quacks_desc)
                    group = GROUP_FRIENDS
                    setSound(Uri.parse("android.resource://${context.packageName}/$res"), notifAttrs)
                    enableVibration(true)
                },
            )
        }
        // The alarm service plays the sound itself with USAGE_ALARM; the channel is silent to avoid doubling.
        nm.createNotificationChannel(NotificationChannel(ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarms_desc)
            setSound(null, null)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(ALARMS_QUIET, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        nm.createNotificationChannel(NotificationChannel(REQUESTS, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.channel_requests_desc); group = GROUP_FRIENDS })
        nm.createNotificationChannel(NotificationChannel(REQUESTS_SILENT, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); group = GROUP_FRIENDS })
        nm.createNotificationChannel(NotificationChannel(MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT).apply { description = context.getString(R.string.channel_messages_desc); group = GROUP_FRIENDS })
        nm.createNotificationChannel(NotificationChannel(SAFETY, context.getString(R.string.channel_safety), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.channel_safety_desc) })
        nm.createNotificationChannel(NotificationChannel(UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply { description = context.getString(R.string.channel_updates_desc) })
    }
}
