package app.wakeme

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * Screenshots and observations that CI collects as evidence. Screenshots are taken by the
 * shell user into /data/local/tmp (adb cannot read app storage on Android 11+); observations
 * go to logcat under the WakeMeEvidence tag. Results from an emulator are not device evidence;
 * they show the code path runs.
 */
object DeviceEvidence {
    private const val DIR = "/data/local/tmp/wakeme-evidence"
    private var dirReady = false

    fun screenshot(name: String) {
        if (!dirReady) {
            shell("mkdir -p $DIR")
            dirReady = true
        }
        shell("screencap -p $DIR/$name.png")
    }

    fun note(name: String, text: String) {
        Log.i("WakeMeEvidence", "$name: $text")
    }

    /** Runs as the shell user; reading the output to the end waits for the command to finish. */
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }
}
