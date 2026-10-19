package app.wakeme

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wakeme.alarm.AlarmAudio
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the alarm playback path (USAGE_ALARM, audio focus, speaker
 * preference, route read-back) and records what the OS reported. An
 * emulator's audio stack is virtual, so this is not evidence that a phone
 * was audible; it proves the calls succeed on the platform version.
 */
@RunWith(AndroidJUnit4::class)
class AlarmAudioTest {
    @Test
    fun alarmPlaybackReportsWhatHappened() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = AlarmAudio(ctx)
        val report = audio.start("bundled:rise.gentle")
        Thread.sleep(1_500)
        val route = audio.currentRoute()
        audio.stop()
        DeviceEvidence.note(
            "alarm-audio",
            "started=${report.started} focusGranted=${report.focusGranted} routeAtStart=${report.routeType} routeAfter1.5s=$route " +
                "fallbackSound=${report.usedFallbackSound} failure=${report.failure} alarmVolumeZero=${audio.alarmVolumeIsZero()}",
        )
        // A player error means the bundled sound or attributes are broken; focus denial is an environment state.
        assertNotEquals("player_error", report.failure)
    }
}
