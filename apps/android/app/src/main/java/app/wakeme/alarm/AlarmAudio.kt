package app.wakeme.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import app.wakeme.R
import java.io.File

/** What actually happened when we tried to play, reported honestly in receipts and diagnostics. */
data class PlaybackReport(
    val started: Boolean,
    val focusGranted: Boolean,
    val routeType: String,
    val usedFallbackSound: Boolean,
    val failure: String? = null,
)

/**
 * Alarm playback: USAGE_ALARM attributes from the alarm foreground service
 * (the Android 17 background-audio path for exact-alarm holders), explicit
 * audio-focus handling (a failed request can be silent, so it is checked),
 * and a *preference* for the built-in speaker that is then verified by
 * reading the actual route. We never disconnect Bluetooth or change the
 * user's alarm volume.
 */
class AlarmAudio(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun resolve(soundId: String): Pair<Uri, Boolean> {
        val bundled = mapOf(
            "bundled:quack.classic" to R.raw.quack_classic,
            "bundled:quack.double" to R.raw.quack_double,
            "bundled:chime.soft" to R.raw.chime_soft,
            "bundled:bell.bright" to R.raw.bell_bright,
            "bundled:rise.gentle" to R.raw.rise_gentle,
            "bundled:pulse.wake" to R.raw.pulse_wake,
        )
        bundled[soundId]?.let { return Uri.parse("android.resource://${context.packageName}/$it") to false }
        if (soundId.startsWith("media:")) {
            val file = File(context.filesDir, "sounds/${soundId.removePrefix("media:")}.audio")
            if (file.exists()) return Uri.fromFile(file) to false
        }
        // Approved custom sound not cached yet: always fall back to a bundled sound rather than silence.
        return Uri.parse("android.resource://${context.packageName}/${R.raw.rise_gentle}") to true
    }

    fun start(soundId: String, preferSpeaker: Boolean = true): PlaybackReport {
        stop()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) player?.pause() }
            .build()
        focusRequest = req
        val focus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        vibrate()
        if (!focus) {
            // e.g. during a phone call: don't blast over it; vibration and the full-screen alarm continue.
            return PlaybackReport(started = false, focusGranted = false, routeType = "none", usedFallbackSound = false, failure = "audio_focus_denied")
        }
        val (uri, fallback) = resolve(soundId)
        return try {
            val mp = MediaPlayer()
            // Own the instance before prepare/start: either operation may fail (missing/corrupt
            // cached media), and the failure path must still release the native player.
            player = mp
            mp.apply {
                setAudioAttributes(attributes)
                setDataSource(context, uri)
                isLooping = true
                prepare()
            }
            if (preferSpeaker) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { mp.setPreferredDevice(it) }
            }
            mp.start()
            PlaybackReport(started = mp.isPlaying, focusGranted = true, routeType = routeName(mp.routedDevice), usedFallbackSound = fallback)
        } catch (e: Exception) {
            releasePlayer()
            PlaybackReport(started = false, focusGranted = true, routeType = "none", usedFallbackSound = fallback, failure = "player_error")
        }
    }

    /** Read the actual output route (may change after start, e.g. when earbuds connect). */
    fun currentRoute(): String = routeName(player?.routedDevice)

    private fun routeName(d: AudioDeviceInfo?): String = when (d?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired"
        null -> "unknown"
        else -> "other"
    }

    private fun vibrate() {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 1200), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vm.defaultVibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            // Android 12: the AudioAttributes overload marks it as alarm vibration.
            @Suppress("DEPRECATION")
            vm.defaultVibrator.vibrate(effect, attributes)
        }
    }

    fun alarmVolumeIsZero(): Boolean = audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0

    fun stop() {
        releasePlayer()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        if (previous != null) {
            try { runCatching { previous.stop() } } finally { previous.release() }
        }
    }
}
